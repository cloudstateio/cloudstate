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

package com.lightbend.statefulserverless.operator

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import akka.stream.Materializer
import com.lightbend.statefulserverless.operator.EventSourcedService.Resource
import skuber.LabelSelector.dsl._
import skuber._
import skuber.json.format._
import skuber.api.client.KubernetesClient
import skuber.apps.v1.Deployment
import skuber.rbac._

import scala.concurrent.{ExecutionContext, Future}
import skuber.json.rbac.format._


class EventSourcedServiceOperatorFactory(implicit mat: Materializer, ec: ExecutionContext)
  extends OperatorFactory[EventSourcedService.Status, Resource] {

  import OperatorConstants._

  override def apply(client: KubernetesClient): Operator = new EventSourcedServiceOperator(client)

  class EventSourcedServiceOperator(client: KubernetesClient) extends Operator {

    override def handleChanged(resource: Resource): Future[Some[EventSourcedService.Status]] = {
      addOrUpdateDeployment(resource)
    }

    override def handleDeleted(resource: Resource): Future[Done] = {
      for {
        maybeExisting <- client.getOption[Deployment](deploymentNameFor(resource.name))
        _ <- maybeExisting match {
          case Some(existing) if existing.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace) =>
            println("Deleting deployment " + existing.name)
            client.delete[Deployment](existing.name)
          case Some(existing) =>
            println(s"Not deleting deployment ${existing.name} because we don't manage it")
            Future.successful(Done)
          case None =>
            println("Deployment to delete not found")
            Future.successful(Done)
        }
        maybeExistingService <- client.getOption[Service](resource.name)
        _ <- maybeExistingService match {
          case Some(existing) if existing.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace) =>
            client.delete[Service](existing.name)
          case Some(existing) =>
            Future.successful(Done)
          case None =>
            Future.successful(Done)
        }
      } yield Done
    }

    override def statusFromError(error: Throwable, existing: Option[Resource]): EventSourcedService.Status = {
      EventSourcedService.Status(
        appliedSpecHash = existing.map(resource => hashOf(resource.spec)),
        journalConfigHash = None,
        appliedJournalConfigHash = None,
        reason = Some(s"Operator encountered unknown error: $error"),
        lastApplied = Some(Instant.now())
      )
    }

    override def hasAnythingChanged(resource: EventSourcedService.Resource): Boolean = {
      (for {
        status <- resource.status
        appliedSpecHash <- status.appliedSpecHash
      } yield {
        val specHash = hashOf(resource.spec)
        if (specHash != appliedSpecHash) {
          true
        } else {
          // If there is a journalConfigHash and it doesn't match the applied hash, then return true
          if (status.journalConfigHash.exists(!status.appliedJournalConfigHash.contains(_))) {
            true
          } else {
            // Otherwise, return true if we are in error, and the timeout has been exceeded
            if (status.reason.isDefined &&
              status.lastApplied.getOrElse(Instant.EPOCH).plus(1, ChronoUnit.MINUTES).isBefore(Instant.now())) {
              true
            } else false
          }
        }
      }) getOrElse true
    }

    private def addOrUpdateDeployment(resource: EventSourcedService.Resource) = {
      for {
        maybeJournal <- client.getOption[EventSourcedJournal.Resource](resource.spec.journal.name)
        status <- maybeJournal match {
          case Some(journal) if journal.status.isDefined =>
            addOrUpdateDeploymentForJournal(resource, journal.name, journal.spec.`type`, journal.status.get)
          case _ =>
            handleDeleted(resource).map(_ =>
              EventSourcedService.Status(
                appliedSpecHash = Some(hashOf(resource.spec)),
                journalConfigHash = None,
                appliedJournalConfigHash = None,
                reason = Some("Journal '" + resource.spec.journal.name + "' not found in namespace '" + resource.namespace + "'."),
                lastApplied = Some(Instant.now())
              )
            )
        }
      } yield Some(status)
    }

    private def addOrUpdateDeploymentForJournal(resource: EventSourcedService.Resource, journalName: String,
      journalType: String, journal: EventSourcedJournal.Status) = {

      val result = for {
        journalConfig <- journalType match {
          case `CassandraJournalType` =>
            resource.spec.journal.config.flatMap(obj => (obj \ "keyspace").asOpt[String]) match {
              case Some(keyspace) =>
                Right(List(EnvVar("CASSANDRA_KEYSPACE", keyspace)))
              case None =>
                Left("No keyspace found for Cassandra journal")
            }
          case _ =>
            Left(s"Journal '$journalName' has unknown journal type '$journalType'")
        }
        image <- journal.image.toRight(s"Journal '$journalName' has no defined image in the status")
      } yield {

        val templateSpec = resource.spec.template.spec
        val injectedSpec = Pod.Spec(
          containers = templateSpec.containers.map {
            case container if container.name == "" => container.copy(name = resource.name)
            case container => container
          })
          .addContainer(
            Container(
              name = "akka-sidecar",
              image = image,
              imagePullPolicy = if (image.endsWith(":latest")) Container.PullPolicy.Always else Container.PullPolicy.IfNotPresent,
              ports = List(
                Container.Port(containerPort = 9000, name = "grpc"),
                Container.Port(containerPort = 8558, name = "management")
              ),
              env = List(
                EnvVar("SELECTOR_LABEL_VALUE", resource.name),
                EnvVar("SELECTOR_LABEL", EventSourcedLabel),
                EnvVar("REQUIRED_CONTACT_POINT_NR", Math.ceil(resource.spec.replicas / 2 + 1).toString),
                // todo where does this come from?
                EnvVar("JAVA_OPTS", "-Xms256m -Xmx256m")
              ) ++ journal.sidecarEnv.getOrElse(Nil) ++ journalConfig,
              resources = Some(Resource.Requirements(
                limits = Map(Resource.memory -> Resource.Quantity("512Mi")),
                requests = Map(
                  Resource.memory -> Resource.Quantity("512Mi"),
                  Resource.cpu -> Resource.Quantity("0.25")
                )
              )),
              readinessProbe = Some(Probe(
                action = HTTPGetAction(
                  port = Right("management"),
                  path = "/ready"
                ),
                periodSeconds = Some(10),
                failureThreshold = Some(10),
                initialDelaySeconds = 20
              )),
              livenessProbe = Some(Probe(
                action = HTTPGetAction(
                  port = Right("management"),
                  path = "/alive"
                ),
                periodSeconds = Some(10),
                failureThreshold = Some(10),
                initialDelaySeconds = 20
              ))
            )
          )

        val deploymentName = deploymentNameFor(resource.name)
        // Create the deployment
        val deployment = Deployment(
          metadata = ObjectMeta(
            name = deploymentName,
            labels = Map(
              KubernetesManagedByLabel -> OperatorNamespace,
              EventSourcedLabel -> resource.name,
              JournalLabel -> journalName
            )
          )
        ).withReplicas(resource.spec.replicas)
          .withLabelSelector(
            EventSourcedLabel is resource.name
          )
          .withTemplate(Pod.Template.Spec(
            metadata = ObjectMeta(
              labels = Map(
                EventSourcedLabel -> resource.name,
                JournalLabel -> journalName
              )
            ),
            spec = Some(injectedSpec)
          ))


        for {
          _ <- ensureRbacPermissionsInNamespace(resource.namespace)
          maybeExisting <- client.getOption[Deployment](deploymentName)
          _ <- maybeExisting match {
            case Some(existing) =>
              client.update(existing.copy(
                spec = deployment.spec,
                metadata = existing.metadata.copy(labels = deployment.metadata.labels)
              ))
            case None =>
              client.create(deployment)
          }
          _ <- addOrUpdateService(resource)
        } yield EventSourcedService.Status(
          appliedSpecHash = Some(hashOf(resource.spec)),
          journalConfigHash = journal.specHash,
          appliedJournalConfigHash = journal.specHash,
          reason = None,
          lastApplied = Some(Instant.now())
        )
      }

      result.fold(
        error => Future.successful(EventSourcedService.Status(
          appliedSpecHash = Some(hashOf(resource.spec)),
          journalConfigHash = None,
          appliedJournalConfigHash = None,
          reason = Some(error),
          lastApplied = Some(Instant.now())
        )),
        identity
      )

    }

    private def addOrUpdateService(resource: Resource): Future[Done] = {
      val spec = Service.Spec(
        ports = List(
          Service.Port(
            port = 80,
            targetPort = Some(9000),
            protocol = Protocol.TCP,
            name = "http2"
          )
        ),
        selector = Map(
          EventSourcedLabel -> resource.name
        ),
        _type = Service.Type.LoadBalancer
      )
      val service = Service(
        spec = Some(spec),
        metadata = ObjectMeta(
          name = resource.name,
          labels = Map(
            KubernetesManagedByLabel -> OperatorNamespace
          )
        )
      )
      for {
        maybeExisting <- client.getOption[Service](resource.name)
        _ <- maybeExisting match {
          case Some(existing) =>
            client.update(existing.copy(
              spec = Some(existing.spec.fold(spec)(_.copy(
                ports = spec.ports,
                selector = spec.selector,
                _type = spec._type
              ))),
              metadata = existing.metadata.copy(labels = service.metadata.labels)
            ))
          case None =>
            client.create(service)
        }
      } yield Done
    }

    private def ensureRbacPermissionsInNamespace(namespace: String) = {
      for {
        _ <- ensurePodReaderRoleExists(namespace)
        _ <- ensurePodReaderRoleBindingExists(namespace)
      } yield ()
    }

    private def ensurePodReaderRoleExists(namespace: String): Future[Role] = {
      val expectedRole = Role(
        metadata = ObjectMeta(
          name = PodReaderRoleName,
          labels = Map(
            KubernetesManagedByLabel -> OperatorNamespace
          )
        ),
        rules = List(
          PolicyRule(
            apiGroups = List(""),
            attributeRestrictions = None,
            nonResourceURLs = Nil,
            resources = List("pods"),
            verbs = List("get", "watch", "list"),
            resourceNames = Nil
          )
        )
      )

      client.getOption[Role](PodReaderRoleName).flatMap {
        case Some(role) =>
          if (role.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace)) {
            // We manage it, check that it's up to date
            if (role.rules != expectedRole.rules) {
              println(s"Found existing managed role '$PodReaderRoleName' but does not match required config, updating...")
              client.update(expectedRole)
            } else {
              println(s"Found existing managed role '$PodReaderRoleName'")
              Future.successful(role)
            }
          } else {
            println(s"Found existing non managed role '$PodReaderRoleName'")
            Future.successful(role)
          }
        case None =>
          println(s"Role '$PodReaderRoleName' not found, creating...")
          client.create(expectedRole)
      }
    }

    private def ensurePodReaderRoleBindingExists(namespace: String): Future[RoleBinding] = {
      val expectedRoleBinding = RoleBinding(
        metadata = ObjectMeta(
          name = PodReaderRoleBindingName,
          labels = Map(
            KubernetesManagedByLabel -> OperatorNamespace
          )
        ),
        subjects = List(
          Subject(
            apiVersion = None,
            kind = "User",
            name = s"system:serviceaccount:$namespace:default",
            namespace = None
          )
        ),
        roleRef = RoleRef(
          apiGroup = "rbac.authorization.k8s.io",
          kind = "Role",
          name = PodReaderRoleName
        )
      )

      client.getOption[RoleBinding](PodReaderRoleBindingName).flatMap {
        case Some(roleBinding) =>
          if (roleBinding.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace)) {
            // We manage it, check that it's up to date
            if (roleBinding.roleRef != expectedRoleBinding.roleRef || roleBinding.subjects != expectedRoleBinding.subjects) {
              println(s"Found existing managed role binding '$PodReaderRoleBindingName' but does not match required config, updating...")
              client.update(expectedRoleBinding)
            } else {
              println(s"Found existing managed role binding '$PodReaderRoleBindingName'")
              Future.successful(roleBinding)
            }
          } else {
            println(s"Found existing non managed role binding '$PodReaderRoleBindingName'")
            Future.successful(roleBinding)
          }
        case None =>
          println(s"Role binding '$PodReaderRoleBindingName' not found, creating...")
          client.create(expectedRoleBinding)
      }
    }

    private def deploymentNameFor(name: String) = "event-sourced-service-" + name
  }
}