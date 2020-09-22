/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.operator

import java.time.ZonedDateTime

import akka.Done
import akka.stream.Materializer
import skuber.LabelSelector.dsl._
import skuber._
import skuber.api.client.KubernetesClient
import skuber.apps.v1.Deployment

import scala.concurrent.{ExecutionContext, Future}
import StatefulService.Resource
import io.cloudstate.operator.stores.{StatefulStoreSupport, StatefulStoreUsageConfiguration}

class StatefulServiceOperatorFactory(implicit mat: Materializer, ec: ExecutionContext)
    extends OperatorFactory[StatefulService.Status, Resource] {

  import OperatorConstants._

  override def apply(client: KubernetesClient, config: OperatorConfig): Operator =
    new StatefulServiceOperator(client, config)

  class StatefulServiceOperator(client: KubernetesClient, config: OperatorConfig) extends Operator {

    private val helper = new ResourceHelper(client)

    private def isOwnedByStatefulServiceController(deployment: Deployment): Boolean =
      deployment.metadata.ownerReferences
        .find(_.controller.contains(true))
        .exists(
          ref =>
            ref.apiVersion.startsWith(CloudStateGroup + "/")
            && ref.kind == StatefulServiceKind
        )

    override def handleChanged(resource: Resource): Future[StatusUpdate] = {
      val deploymentName = deploymentNameFor(resource)

      val validatedStoreUsage = resource.spec.datastore match {
        case Some(store) => validateStore(store)
        case None => Validated(StatefulStoreSupport.noStoreUsageConfiguration)
      }
      val validatedDeployment = lookupDeployment(deploymentName)

      val result = for {
        (storeUsage, maybeDeployment) <- validatedStoreUsage.zip(validatedDeployment)
        statusUpdate <- reconcileDeployment(resource, storeUsage, maybeDeployment)
      } yield statusUpdate

      result.fold(errors => updateCondition(resource, errors: _*), identity)
    }

    private def validateStore(store: StatefulService.StatefulStore): Validated[StatefulStoreUsageConfiguration] =
      for {
        storeResource <- lookupStore(store.name)
        storeSupport <- StatefulStoreSupport.get(storeResource)
        storeConfig <- storeSupport.validate(storeResource, client)
        usageConfiguration <- storeConfig.validateInstance(store.config, client)
      } yield usageConfiguration

    private def lookupDeployment(deploymentName: String): Validated[Option[Deployment]] =
      Validated.future(client.getOption[Deployment](deploymentName)).flatMap {
        case Some(deployment) if isOwnedByStatefulServiceController(deployment) =>
          Validated(Some(deployment))
        case Some(_) =>
          Validated.error(ConditionResourcesAvailable,
                          ConditionResourcesAvailableNotOwned,
                          s"There is an existing Deployment $deploymentName that we do not own.")
        case None => Validated(None)
      }

    private def lookupStore(storeName: String): Validated[StatefulStore.Resource] =
      client.getOption[StatefulStore.Resource](storeName).map {
        case Some(store) => Validated(store)
        case None =>
          Validated.error(StatefulStoreConditionType,
                          "StatefulStoreNotFound",
                          s"StatefulStore with name $storeName not found.")
      }

    override def handleDeleted(resource: Resource): Future[Done] =
      for {
        maybeExisting <- client.getOption[Deployment](deploymentNameFor(resource))
        _ <- maybeExisting match {
          case Some(existing) if isOwnedByStatefulServiceController(existing) =>
            println("Deleting deployment " + existing.name)
            client.delete[Deployment](existing.name)
          case Some(existing) =>
            println(s"Not deleting deployment ${existing.name} because we don't manage it")
            Future.successful(Done)
          case None =>
            println("Deployment to delete not found")
            Future.successful(Done)
        }
      } yield Done

    override def statusFromError(error: Throwable, existing: Option[Resource]): StatusUpdate =
      existing match {
        case Some(service) =>
          updateCondition(
            service,
            Condition(
              `type` = ConditionResourcesAvailable,
              status = UnknownStatus,
              reason = Some("UnknownError"),
              message = Some(error.getMessage),
              lastTransitionTime = Some(ZonedDateTime.now())
            )
          )
        case None =>
          println("Unknown error handling service change, but we don't have an existing service to update: " + error)
          error.printStackTrace()
          StatusUpdate.None
      }

    private def updateCondition(service: Resource, conditions: Condition*): StatusUpdate = {
      val status = service.status.getOrElse(new StatefulService.Status(Nil))

      if (conditions.forall(
            condition =>
              status.conditions.exists(
                c =>
                  c.`type` == condition.`type` &&
                  c.status == condition.status &&
                  c.reason == condition.reason
              )
          )) {
        // Hasn't changed, don't update.
        StatusUpdate.None
      } else {
        // Otherwise, update.
        val newConditions = status.conditions.map { condition =>
            conditions.find(_.`type` == condition.`type`).getOrElse(condition)
          } ++ conditions.filter(c => !status.conditions.exists(_.`type` == c.`type`))

        StatusUpdate.Update(status.copy(conditions = newConditions))
      }
    }

    private def reconcileDeployment(service: Resource,
                                    store: StatefulStoreUsageConfiguration,
                                    maybeDeployment: Option[Deployment]): Validated[StatusUpdate] = {

      val deploymentName = deploymentNameFor(service)
      val newDeployment = createDeployment(service, store)

      val createdDeployment = maybeDeployment match {
        case Some(existing) =>
          // todo why will the spec be None?
          val existingSpec = existing.spec.get
          val desired = existing
            .copy(spec = newDeployment.spec)
            // Preserve current scale
            .withReplicas(existingSpec.replicas.getOrElse(1))
            // Selector is immutable so preserve that too
            .withLabelSelector(existingSpec.selector)

          if (desired.spec != existing.spec) {
            val desiredWithLabels = desired.copy(
              metadata = desired.metadata.copy(
                labels = desired.metadata.labels ++ newDeployment.metadata.labels
              )
            )
            Validated.future(client.update(desiredWithLabels).map { updated =>
              if (updated.spec != existing.spec) {
                println("Updated deployment spec.")
                println("Differences were: " + (updated.spec.toString diff existing.spec.toString))
              }
            })
          } else {
            Validated(())
          }

        case None =>
          Validated.future(client.create(newDeployment))
      }

      for {
        _ <- ensureOtherObjectsExist(service, service.spec.serviceAccountName.getOrElse("default"), deploymentName)
        _ <- createdDeployment
      } yield updateCondition(
        service,
        Condition(
          `type` = StatefulStoreConditionType,
          status = TrueStatus,
          severity = Some("Info"),
          lastTransitionTime = Some(ZonedDateTime.now())
        ) :: Condition(
          `type` = ConditionResourcesAvailable,
          status = TrueStatus,
          severity = Some("Info"),
          lastTransitionTime = Some(ZonedDateTime.now())
        ) :: store.successfulConditions: _*
      )
    }

    private def createSideCar(service: Resource, store: StatefulStoreUsageConfiguration) = {
      val jvmMemory = service.spec.sidecarJvmMemory.getOrElse("256m")
      val sidecarResources = service.spec.sidecarResources.getOrElse(
        Resource.Requirements(
          limits = Map(
            Resource.memory -> Resource.Quantity("512Mi")
          ),
          requests = Map(
            Resource.memory -> Resource.Quantity("512Mi"),
            Resource.cpu -> Resource.Quantity("400m")
          )
        )
      )

      val userPort = service.spec.containers.flatMap(_.ports).headOption.fold(DefaultUserPort)(_.containerPort)

      val autoscalingEnvVars: List[EnvVar] = service.spec.autoscaling match {
        case Some(autoscaling) =>
          Seq[(String, Option[_])](
            "AUTOSCALER_ENABLED" -> autoscaling.enabled,
            "USER_FUNCTION_TARGET_CONCURRENCY" -> autoscaling.userFunctionTargetConcurrency,
            "REQUEST_TARGET_CONCURRENCY" -> autoscaling.requestTargetConcurrency,
            "TARGET_CONCURRENCY_WINDOW" -> autoscaling.targetConcurrencyWindow,
            "SCALE_UP_STABLE_DEADLINE" -> autoscaling.scaleUpStableDeadline,
            "SCALE_DOWN_STABLE_DEADLINE" -> autoscaling.scaleDownStableDeadline,
            "REQUEST_RATE_THRESHOLD_FACTOR" -> autoscaling.requestRateThresholdFactor,
            "REQUEST_RATE_WINDOW" -> autoscaling.requestRateWindow
          ).foldLeft(List.empty[EnvVar]) {
            case (list, (name, Some(value))) => EnvVar(name, value.toString) :: list
            case (list, _) => list
          }

        case None => Nil
      }

      val image = store.proxyImage(config.images)

      Container(
        name = "akka-sidecar",
        image = image,
        imagePullPolicy =
          if (image.endsWith(":latest")) Container.PullPolicy.Always else Container.PullPolicy.IfNotPresent,
        ports = List(
          Container.Port(containerPort = KnativeSidecarH2cPort, name = "grpc-proxy"),
          Container.Port(containerPort = MetricsPort, name = MetricsPortName)
        ),
        env = store.proxyContainerEnvVars ::: autoscalingEnvVars ::: List(
            EnvVar("HTTP_PORT", KnativeSidecarH2cPort.toString),
            EnvVar("METRICS_PORT", MetricsPort.toString),
            EnvVar("USER_FUNCTION_PORT", userPort.toString),
            EnvVar("REMOTING_PORT", AkkaRemotingPort.toString),
            EnvVar("MANAGEMENT_PORT", AkkaManagementPort.toString),
            EnvVar("SELECTOR_LABEL_VALUE", service.name),
            EnvVar("SELECTOR_LABEL", StatefulServiceLabel),
            EnvVar("REQUIRED_CONTACT_POINT_NR", "1"),
            EnvVar("JAVA_OPTS", s"-Xms$jvmMemory -Xmx$jvmMemory")
          ),
        resources = Some(sidecarResources),
        readinessProbe = Some(
          Probe(
            action = HTTPGetAction(
              port = Left(AkkaManagementPort),
              path = "/ready"
            ),
            periodSeconds = Some(2),
            failureThreshold = Some(20),
            initialDelaySeconds = 2
          )
        ),
        livenessProbe = Some(
          Probe(
            action = HTTPGetAction(
              port = Left(AkkaManagementPort),
              path = "/alive"
            ),
            periodSeconds = Some(2),
            failureThreshold = Some(20),
            initialDelaySeconds = 2
          )
        )
      )
    }

    private def createDeployment(service: Resource, store: StatefulStoreUsageConfiguration) = {
      val sidecar = createSideCar(service, store)

      // todo perhaps validate?
      val orig = service.spec.containers.head

      val userPort = orig.ports.headOption.fold(DefaultUserPort)(_.containerPort)

      // This is primarily copied from the Knative revision operator
      val userContainer = orig.copy(
        name = UserContainerName,
        volumeMounts = orig.volumeMounts :+ Volume.Mount("varlog", "/var/log"),
        ports = List(
          Container.Port(
            name = UserPortName,
            containerPort = userPort
          )
        ),
        env = orig.env ++ List(
            EnvVar(UserPortEnvVar, userPort.toString)
          ),
        stdin = Some(false),
        tty = Some(false),
        image = orig.image,
        terminationMessagePolicy = orig.terminationMessagePolicy
          .orElse(Some(Container.TerminationMessagePolicy.FallbackToLogsOnError))
      )

      val podSpec = Pod.Spec(
        containers = List(
          userContainer,
          sidecar
        ),
        volumes = service.spec.volumes.getOrElse(Nil) :+ Volume("varlog", Volume.EmptyDir()),
        serviceAccountName = service.spec.serviceAccountName.getOrElse(""),
        tolerations = service.spec.tolerations.getOrElse(Nil),
        nodeSelector = service.spec.nodeSelector.getOrElse(Map.empty)
      )

      val deploymentName = deploymentNameFor(service)

      val labels = {
        val ls = service.metadata.labels ++ Map(
            StatefulServiceLabel -> service.name,
            StatefulServiceUidLabel -> service.uid
          ) ++ service.spec.datastore.map(ds => StatefulStoreLabel -> ds.name)
        if (!ls.contains("app")) ls + ("app" -> service.name)
        else ls
      }
      val annotations = service.metadata.annotations - LastPinnedLabel
      val podAnnotations = annotations ++ Seq(
          "traffic.sidecar.istio.io/includeInboundPorts" -> s"$KnativeSidecarH2cPort",
          "traffic.sidecar.istio.io/excludeOutboundPorts" -> s"$AkkaRemotingPort,$AkkaManagementPort,9042"
        )

      // Create the deployment
      Deployment(
        metadata = ObjectMeta(
          name = deploymentName,
          namespace = service.metadata.namespace,
          labels = labels,
          annotations = annotations,
          ownerReferences = List(
            OwnerReference(
              apiVersion = service.apiVersion,
              kind = service.kind,
              name = service.name,
              uid = service.uid,
              controller = Some(true),
              blockOwnerDeletion = Some(true)
            )
          )
        )
        // Replicas must initially be 1, Knative will verify that the pods come up before it marks the revision ready,
        // and then it scales back down to zero.
      ).withReplicas(1)
        .withLabelSelector(
          StatefulServiceUidLabel is service.uid
        )
        .withTemplate(
          Pod.Template.Spec(
            metadata = ObjectMeta(
              labels = labels,
              annotations = podAnnotations
            ),
            spec = Some(podSpec)
          )
        )
    }

    private def ensureOtherObjectsExist(service: Resource,
                                        serviceAccountName: String,
                                        deploymentName: String): Validated[Done] =
      Validated.future(for {
        _ <- helper.ensurePodReaderRoleExists()
        _ <- helper.ensurePodReaderRoleBindingExists(serviceAccountName)
        _ <- helper.ensureDeploymentScalerRoleExists(deploymentName, service)
        _ <- helper.ensureDeploymentScalerRoleBindingExists(serviceAccountName, service)
        _ <- helper.ensureServiceForStatefulServiceExists(service)
      } yield Done)

    private def deploymentNameFor(revision: Resource) = revision.metadata.name + "-deployment"
  }
}
