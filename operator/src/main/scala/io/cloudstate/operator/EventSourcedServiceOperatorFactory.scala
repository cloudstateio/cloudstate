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

import EventSourcedService.Resource

class EventSourcedServiceOperatorFactory(implicit mat: Materializer, ec: ExecutionContext)
  extends OperatorFactory[EventSourcedService.Status, Resource] {

  private val CassandraJournalImage = sys.env.getOrElse("CASSANDRA_JOURNAL_IMAGE", "gcr.io/stateserv/cloudstate-proxy-cassandra:latest")

  import OperatorConstants._

  override def apply(client: KubernetesClient): Operator = new EventSourcedServiceOperator(client)

  class EventSourcedServiceOperator(client: KubernetesClient) extends Operator {

    private val helper = new ResourceHelper(client)

    private def isOwnedByEventSourcedServiceController(deployment: Deployment): Boolean = {
      deployment.metadata.ownerReferences
        .find(_.controller.contains(true))
        .exists(ref => ref.apiVersion.startsWith(CloudStateGroup + "/")
          && ref.kind == EventSourcedServiceKind)
    }

    override def handleChanged(resource: Resource): Future[StatusUpdate] = {
      val deploymentName = deploymentNameFor(resource)

      for {
        maybeJournal <- client.getOption[EventSourcedJournal.Resource](resource.spec.journal.name)
        maybeDeployment <- client.getOption[Deployment](deploymentName)
        statusUpdate <- reconcileDeployment(resource, maybeJournal, maybeDeployment)
      } yield statusUpdate
    }

    override def handleDeleted(resource: Resource): Future[Done] = {

      for {
        maybeExisting <- client.getOption[Deployment](deploymentNameFor(resource))
        _ <- maybeExisting match {
          case Some(existing) if isOwnedByEventSourcedServiceController(existing) =>
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

    }

    override def statusFromError(error: Throwable, existing: Option[Resource]): StatusUpdate = {
      existing match {
        case Some(service) =>
          updateCondition(service, EventSourcedService.Condition(
            `type` = ConditionResourcesAvailable,
            status = UnknownStatus,
            reason = Some("UnknownError"),
            message = Some(error.getMessage),
            lastTransitionTime = Some(ZonedDateTime.now())
          ))
        case None =>
          println("Unknown error handling service change, but we don't have an existing service to update: " + error)
          error.printStackTrace()
          StatusUpdate.None
      }
    }

    private def updateCondition(service: Resource, conditions: EventSourcedService.Condition*): StatusUpdate = {
      val status = service.status.getOrElse(new EventSourcedService.Status(Nil))

      if (conditions.forall(condition => status.conditions.exists(c =>
        c.`type` == condition.`type` &&
          c.status == condition.status &&
          c.reason == condition.reason
      ))) {
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

    private def reconcileDeployment(service: Resource, maybeJournal: Option[EventSourcedJournal.Resource],
      maybeDeployment: Option[Deployment]) = {

      val deploymentName = deploymentNameFor(service)

      // for expression over eithers, only progresses when they return Right, otherwise we end up with Left of condition
      val result = for {
        _ <- verifyWeOwnDeployment(deploymentName, maybeDeployment)
        sidecar <- validateJournal(service, maybeJournal)
      } yield {

        val newDeployment = createDeployment(service, sidecar)

        val deploymentFuture = maybeDeployment match {
          case Some(existing) =>
            // todo why will the spec be None?
            val existingSpec = existing.spec.get
            val desired = existing.copy(spec = newDeployment.spec)
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
              client.update(desiredWithLabels).map { updated =>
                if (updated.spec != existing.spec) {
                  println("Updated deployment spec.")
                  println("Differences were: " + (updated.spec.toString diff existing.spec.toString))
                }
              }
            } else {
              Future.successful(())
            }

          case None =>
            client.create(newDeployment)
        }

        for {
          _ <- ensureOtherObjectsExist(service, service.spec.serviceAccountName.getOrElse("default"), deploymentName)
          _ <- deploymentFuture
        } yield updateCondition(service, EventSourcedService.Condition(
          `type` = JournalConditionType,
          status = TrueStatus,
          severity = Some("Info"),
          lastTransitionTime = Some(ZonedDateTime.now())
        ), EventSourcedService.Condition(
          `type` = ConditionResourcesAvailable,
          status = TrueStatus,
          severity = Some("Info"),
          lastTransitionTime = Some(ZonedDateTime.now())
        ))
      }

      result match {
        case Left(error) =>
          Future.successful(updateCondition(service, error))
        case Right(action) =>
          action
      }
    }

    private def errorCondition(`type`: String, reason: String, message: String) = {
      EventSourcedService.Condition(`type`, FalseStatus, Some("Error"), Some(ZonedDateTime.now()), Some(reason), Some(message))
    }

    private def verifyWeOwnDeployment(name: String, maybeDeployment: Option[Deployment]): Either[EventSourcedService.Condition, Done] = {
      maybeDeployment match {
        case None =>
          Right(Done)
        case Some(deployment) =>
          if (isOwnedByEventSourcedServiceController(deployment)) {
            Right(Done)
          } else {
            Left(errorCondition(ConditionResourcesAvailable, ConditionResourcesAvailableNotOwned,
              s"There is an existing Deployment $name that we do not own."))
          }
      }
    }

    private def validateJournal(service: Resource, maybeJournal: Option[EventSourcedJournal.Resource]): Either[EventSourcedService.Condition, Container] = {
      maybeJournal match {
        case None =>
          Left(errorCondition(JournalConditionType, "JournalNotFound", s"Journal with name ${service.spec.journal.name} not found."))
        case Some(journal) =>
          journal.spec.`type` match {
            case `CassandraJournalType` =>
              journal.spec.deployment match {
                case `UnmanagedJournalDeployment` =>
                  (journal.spec.config \ "service").asOpt[String] match {
                    case Some(serviceName) =>
                      service.spec.journal.config.flatMap(config => (config \ "keyspace").asOpt[String]) match {
                        case Some(keyspace) =>
                          Right(createCassandraSideCar(service, serviceName, keyspace))
                        case None =>
                          Left(errorCondition(JournalConditionType, "MissingKeyspace",
                            "No keyspace declared for Cassandra journal"))
                      }
                    case None =>
                      Left(errorCondition(JournalConditionType, "MissingServiceName",
                        "No service name declared in unmanaged Cassandra journal"))
                  }
                case unknown =>
                  Left(errorCondition(JournalConditionType, "UnknownDeploymentType",
                    s"Unknown Cassandra deployment type: $unknown, supported types for Cassandra are: Unmanaged"))
              }
            case unknown =>
              Left(errorCondition(JournalConditionType, "UnknownJournalType",
                s"Unknown journal type: $unknown, supported types are: Cassandra"))
          }
      }
    }

    private def createCassandraSideCar(serviceResource: Resource, serviceName: String, keyspace: String) = {
      createSideCar(serviceResource, CassandraJournalImage, List(
        EnvVar("CASSANDRA_CONTACT_POINTS", serviceName),
        EnvVar("CASSANDRA_KEYSPACE", keyspace)
      ))
    }

    private def createSideCar(service: Resource, image: String, env: List[EnvVar]) = {
      val jvmMemory = service.spec.sidecarJvmMemory.getOrElse("256m")
      val sidecarResources = service.spec.sidecarResources.getOrElse(Resource.Requirements(
        limits = Map(
          Resource.memory -> Resource.Quantity("512Mi")
        ),
        requests = Map(
          Resource.memory -> Resource.Quantity("512Mi"),
          Resource.cpu -> Resource.Quantity("400m")
        )
      ))

      val userPort = service.spec.containers.flatMap(_.ports).headOption.fold(DefaultUserPort)(_.containerPort)

      val autoscalingEnvVars: List[EnvVar] = service.spec.autoscaling match {
        case Some(autoscaling) =>
          Seq[(String, Option[_])](
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

      Container(
        name = "akka-sidecar",
        image = image,
        imagePullPolicy = if (image.endsWith(":latest")) Container.PullPolicy.Always else Container.PullPolicy.IfNotPresent,
        ports = List(
          Container.Port(containerPort = KnativeSidecarH2cPort, name = "grpc-proxy")
        ),
        env = env ::: autoscalingEnvVars ::: List(
          EnvVar("HTTP_PORT", KnativeSidecarH2cPort.toString),
          EnvVar("USER_FUNCTION_PORT", userPort.toString),
          EnvVar("REMOTING_PORT", AkkaRemotingPort.toString),
          EnvVar("MANAGEMENT_PORT", AkkaManagementPort.toString),
          EnvVar("SELECTOR_LABEL_VALUE", service.name),
          EnvVar("SELECTOR_LABEL", EventSourcedServiceLabel),
          EnvVar("REQUIRED_CONTACT_POINT_NR", "1"),
          EnvVar("JAVA_OPTS", s"-Xms$jvmMemory -Xmx$jvmMemory")
        ),
        resources = Some(sidecarResources),
        readinessProbe = Some(Probe(
          action = HTTPGetAction(
            port = Left(AkkaManagementPort),
            path = "/ready"
          ),
          periodSeconds = Some(2),
          failureThreshold = Some(20),
          initialDelaySeconds = 2
        )),
        livenessProbe = Some(Probe(
          action = HTTPGetAction(
            port = Left(AkkaManagementPort),
            path = "/alive"
          ),
          periodSeconds = Some(2),
          failureThreshold = Some(20),
          initialDelaySeconds = 2
        ))
      )

    }

    private def createDeployment(service: Resource, sidecar: Container) = {

      // todo perhaps validate?
      val orig = service.spec.containers.head

      val userPort = orig.ports.headOption.fold(DefaultUserPort)(_.containerPort)

      // This is primarily copied from the Knative revision operator
      val userContainer = orig.copy(
        name = UserContainerName,
        volumeMounts = orig.volumeMounts :+ Volume.Mount("varlog", "/var/log"),
        ports = List(Container.Port(
          name = UserPortName,
          containerPort = userPort
        )),
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
          EventSourcedServiceLabel -> service.name,
          EventSourcedServiceUidLabel -> service.uid,
          JournalLabel -> service.spec.journal.name,
        )
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
          EventSourcedServiceUidLabel is service.uid
        )
        .withTemplate(Pod.Template.Spec(
          metadata = ObjectMeta(
            labels = labels,
            annotations = podAnnotations
          ),
          spec = Some(podSpec)
        ))
    }

    private def ensureOtherObjectsExist(service: Resource, serviceAccountName: String, deploymentName: String) = {
      for {
        _ <- helper.ensurePodReaderRoleExists()
        _ <- helper.ensurePodReaderRoleBindingExists(serviceAccountName)
        _ <- helper.ensureDeploymentScalerRoleExists(deploymentName, service)
        _ <- helper.ensureDeploymentScalerRoleBindingExists(serviceAccountName, service)
        _ <- helper.ensureServiceForEssExists(service)
      } yield ()
    }

    private def deploymentNameFor(revision: Resource) = revision.metadata.name + "-deployment"
  }
}