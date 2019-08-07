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

import KnativeRevision._

class KnativeRevisionOperatorFactory(implicit mat: Materializer, ec: ExecutionContext)
  extends OperatorFactory[KnativeRevision.Status, Resource] {

  private val CassandraJournalImage = sys.env.getOrElse("CASSANDRA_JOURNAL_IMAGE", "gcr.io/stateserv/cloudstate-proxy-cassandra:latest")

  import OperatorConstants._

  override def apply(client: KubernetesClient): Operator = new KnativeRevisionOperator(client)

  class KnativeRevisionOperator(client: KubernetesClient) extends Operator {

    private val helper = new ResourceHelper(client)

    private def isOwnedByKnativeRevisionController(deployment: Deployment): Boolean = {
      deployment.metadata.ownerReferences
        .find(_.controller.contains(true))
        .exists(ref => ref.apiVersion.startsWith(KnativeServingGroup + "/")
          && ref.kind == RevisionKind)
    }

    override def handleChanged(resource: Resource): Future[StatusUpdate] = {
      resource.spec.deployer match {
        case Some(esd: CloudStateDeployer) =>
          reconcile(resource, esd)
        case _ =>
          Future.successful(StatusUpdate.None)
      }
    }

    override def handleDeleted(resource: Resource): Future[Done] = {

      resource.spec.deployer match {
        case Some(esd: CloudStateDeployer) =>
          for {
            maybeExisting <- client.getOption[Deployment](deploymentNameFor(resource))
            _ <- maybeExisting match {
              case Some(existing) if isOwnedByKnativeRevisionController(existing) =>
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
        case _ =>
          Future.successful(Done)
      }

    }

    override def statusFromError(error: Throwable, existing: Option[Resource]): StatusUpdate = {
      existing match {
        case Some(revision) =>
          updateCondition(revision, KnativeRevision.Condition(
            `type` = JournalConditionType,
            status = UnknownStatus,
            reason = Some("UnknownError"),
            message = Some(error.getMessage),
            lastTransitionTime = Some(ZonedDateTime.now())
          ))
        case None =>
          println("Unknown error handling revision change, but we don't have an existing revision to update: " + error)
          error.printStackTrace()
          StatusUpdate.None
      }
    }

    private def updateCondition(revision: KnativeRevision.Resource, condition: KnativeRevision.Condition): StatusUpdate = {
      val status = revision.status.getOrElse(new KnativeRevision.Status(None, Nil, None, None, None))
      // First check if the condition has actually changed - important, because otherwise we might end up in an
      // infinite loop with the Knative operator
      if (status.conditions.exists(c =>
        c.`type` == condition.`type` &&
          c.status == condition.status &&
          c.reason == condition.reason
      )) {
        // Hasn't changed, don't update.
        StatusUpdate.None
      } else {
        // Otherwise, update.
        val conditions = if (status.conditions.exists(_.`type` == condition.`type`)) {
          status.conditions.map {
            case c if c.`type` == condition.`type` => condition
            case other => other
          }
        } else {
          status.conditions :+ condition
        }
        StatusUpdate.Update(status.copy(conditions = conditions))
      }
    }

    private def reconcile(revision: KnativeRevision.Resource, deployer: CloudStateDeployer) = {
      val deploymentName = deploymentNameFor(revision)

      for {
        maybeJournal <- client.getOption[Journal.Resource](deployer.journal.name)
        maybeDeployment <- client.getOption[Deployment](deploymentName)
        statusUpdate <- reconcileDeployment(revision, deployer, maybeJournal, maybeDeployment)
      } yield statusUpdate
    }

    private def reconcileDeployment(revision: KnativeRevision.Resource, deployer: CloudStateDeployer,
      maybeJournal: Option[Journal.Resource], maybeDeployment: Option[Deployment]) = {
      val deploymentName = deploymentNameFor(revision)

      // for expression over eithers, only progresses when they return Right, otherwise we end up with Left of condition
      val result = for {
        _ <- verifyWeOwnDeployment(deploymentName, maybeDeployment)
        sidecar <- validateJournal(revision, deployer, maybeJournal)
      } yield {

        val newDeployment = createDeployment(revision, deployer, sidecar)

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
          _ <- ensureRbacPermissionsInNamespace(revision.spec.serviceAccountName.getOrElse("default"))
          _ <- deploymentFuture
        } yield updateCondition(revision, KnativeRevision.Condition(
          `type` = JournalConditionType,
          status = TrueStatus,
          severity = Some("Info"),
          lastTransitionTime = Some(ZonedDateTime.now())
        ))
      }

      result match {
        case Left(error) =>
          Future.successful(updateCondition(revision, error))
        case Right(action) =>
          action
      }
    }

    private def errorCondition(`type`: String, reason: String, message: String) = {
      KnativeRevision.Condition(`type`, FalseStatus, Some("Error"), Some(ZonedDateTime.now()), Some(reason), Some(message))
    }

    private def verifyWeOwnDeployment(name: String, maybeDeployment: Option[Deployment]): Either[KnativeRevision.Condition, Done] = {
      maybeDeployment match {
        case None =>
          Right(Done)
        case Some(deployment) =>
          if (isOwnedByKnativeRevisionController(deployment)) {
            Right(Done)
          } else {
            Left(errorCondition(ConditionResourcesAvailable, ConditionResourcesAvailableNotOwned,
              s"There is an existing Deployment $name that we do not own."))
          }
      }
    }

    private def validateJournal(revision: KnativeRevision.Resource, deployer: CloudStateDeployer,
      maybeJournal: Option[Journal.Resource]): Either[KnativeRevision.Condition, Container] = {
      maybeJournal match {
        case None =>
          Left(errorCondition(JournalConditionType, "JournalNotFound", s"Journal with name ${deployer.journal.name} not found."))
        case Some(journal) =>
          journal.spec.`type` match {
            case `CassandraJournalType` =>
              journal.spec.deployment match {
                case `UnmanagedJournalDeployment` =>
                  (journal.spec.config \ "service").asOpt[String] match {
                    case Some(serviceName) =>
                      deployer.journal.config.flatMap(config => (config \ "keyspace").asOpt[String]) match {
                        case Some(keyspace) =>
                          Right(createCassandraSideCar(revision, deployer, serviceName, keyspace))
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

    private def createCassandraSideCar(revision: KnativeRevision.Resource, deployer: CloudStateDeployer,
      service: String, keyspace: String) = {
      createSideCar(revision, deployer, CassandraJournalImage, List(
        EnvVar("CASSANDRA_CONTACT_POINTS", service),
        EnvVar("CASSANDRA_KEYSPACE", keyspace)
      ))
    }

    private def createSideCar(revision: KnativeRevision.Resource, deployer: CloudStateDeployer, image: String, env: Seq[EnvVar]) = {
      val jvmMemory = deployer.sidecarJvmMemory.getOrElse("256m")
      val sidecarResources = deployer.sidecarResources.getOrElse(Resource.Requirements(
        limits = Map(
          Resource.memory -> Resource.Quantity("512Mi")
        ),
        requests = Map(
          Resource.memory -> Resource.Quantity("512Mi"),
          Resource.cpu -> Resource.Quantity("400m")
        )
      ))

      val userPort = revision.spec.containers.flatMap(_.ports).headOption.fold(DefaultUserPort)(_.containerPort)
      val configuration = revision.metadata.labels.getOrElse(ConfigurationLabel, "")

      Container(
        name = "akka-sidecar",
        image = image,
        imagePullPolicy = if (image.endsWith(":latest")) Container.PullPolicy.Always else Container.PullPolicy.IfNotPresent,
        ports = List(
          Container.Port(containerPort = KnativeSidecarH2cPort, name = KnativeSidecarPortName),
          Container.Port(containerPort = MetricsPort, name = MetricsPortName)
        ),
        env = List(
          EnvVar("HTTP_PORT", KnativeSidecarH2cPort.toString),
          EnvVar("USER_FUNCTION_PORT", userPort.toString),
          EnvVar("REMOTING_PORT", AkkaRemotingPort.toString),
          EnvVar("MANAGEMENT_PORT", AkkaManagementPort.toString),
          EnvVar("METRICS_PORT", MetricsPort.toString),
          EnvVar("SELECTOR_LABEL_VALUE", configuration),
          EnvVar("SELECTOR_LABEL", ConfigurationLabel),
          EnvVar("CONTAINER_CONCURRENCY", revision.spec.containerConcurrency.getOrElse(0).toString),
          EnvVar("REVISION_TIMEOUT", revision.spec.timeoutSeconds.getOrElse(10) + "s"),
          EnvVar("SERVING_NAMESPACE", revision.namespace),
          EnvVar("SERVING_CONFIGURATION", configuration),
          EnvVar("SERVING_REVISION", revision.name),
          EnvVar("SERVING_POD", EnvVar.FieldRef("metadata.name")),
          // todo this should be based on minscale
          EnvVar("REQUIRED_CONTACT_POINT_NR", "1"),
          EnvVar("JAVA_OPTS", s"-Xms$jvmMemory -Xmx$jvmMemory")
        ) ++ env,
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

    private def createDeployment(revision: KnativeRevision.Resource, deployer: CloudStateDeployer, sidecar: Container) = {

      // validate? It should already be validated.
      val orig = revision.spec.containers.head

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
          EnvVar(UserPortEnvVar, userPort.toString),
          EnvVar(KnativeRevisionEnvVar, revision.name),
          EnvVar(KnativeConfigruationEnvVar, EnvVar.StringValue(revision.metadata.labels.getOrElse(ConfigurationLabel, ""))),
          EnvVar(KnativeServiceEnvVar, EnvVar.StringValue(revision.metadata.labels.getOrElse(ServiceLabel, "")))
        ),
        stdin = Some(false),
        tty = Some(false),
        image = revision.status
          .flatMap(_.imageDigest)
          .filterNot(_ == "")
          .getOrElse(orig.image),
        terminationMessagePolicy = orig.terminationMessagePolicy
          .orElse(Some(Container.TerminationMessagePolicy.FallbackToLogsOnError))
      )

      val podSpec = Pod.Spec(
        containers = List(
          userContainer,
          sidecar
        ),
        volumes = revision.spec.volumes.getOrElse(Nil) :+ Volume("varlog", Volume.EmptyDir()),
        serviceAccountName = revision.spec.serviceAccountName.getOrElse(""),
        terminationGracePeriodSeconds = revision.spec.timeoutSeconds.map(_.asInstanceOf[Int]),
        tolerations = List(
          Pod.EqualToleration(
            key = "loadtest",
            value = Some("cloudstate")
          )
        )
      )

      val deploymentName = deploymentNameFor(revision)

      val labels = {
        val ls = revision.metadata.labels ++ Map(
          RevisionLabel -> revision.name,
          RevisionUidLabel -> revision.uid,
          JournalLabel -> deployer.journal.name
        )
        if (!ls.contains("app")) ls + ("app" -> revision.name)
        else ls
      }
      val annotations = revision.metadata.annotations - LastPinnedLabel
      val podAnnotations = annotations ++ Seq(
        "traffic.sidecar.istio.io/includeInboundPorts" -> s"$KnativeSidecarH2cPort",
        "traffic.sidecar.istio.io/excludeOutboundPorts" -> s"$AkkaRemotingPort,$AkkaManagementPort"
      )

      // Create the deployment
      Deployment(
        metadata = ObjectMeta(
          name = deploymentName,
          namespace = revision.metadata.namespace,
          labels = labels,
          annotations = annotations,
          ownerReferences = List(
            OwnerReference(
              apiVersion = KnativeServingApiVersion,
              kind = RevisionKind,
              name = revision.name,
              uid = revision.uid,
              controller = Some(true),
              blockOwnerDeletion = Some(true)
            )
          )
        )
        // Replicas must initially be 1, Knative will verify that the pods come up before it marks the revision ready,
        // and then it scales back down to zero.
      ).withReplicas(1)
        .withLabelSelector(
          RevisionUidLabel is revision.uid
        )
        .withTemplate(Pod.Template.Spec(
          metadata = ObjectMeta(
            labels = labels,
            annotations = podAnnotations
          ),
          spec = Some(podSpec)
        ))
    }

    private def ensureRbacPermissionsInNamespace(serviceAccountName: String) = {
      for {
        _ <- helper.ensurePodReaderRoleExists()
        _ <- helper.ensurePodReaderRoleBindingExists(serviceAccountName)
      } yield ()
    }

    // Must match https://github.com/knative/serving/blob/2297b69327bbc457563cefc7d36a848159a4c7c0/pkg/reconciler/revision/resources/names/names.go#L24
    private def deploymentNameFor(revision: Resource) = revision.metadata.name + "-deployment"
  }
}