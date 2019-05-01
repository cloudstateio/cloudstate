package com.lightbend.statefulserverless.operator

import akka.Done
import akka.stream.Materializer
import com.lightbend.statefulserverless.operator.EventSourcedServiceConfiguration.Resource
import skuber.LabelSelector.dsl._
import skuber._
import skuber.json.format._
import skuber.api.client.KubernetesClient
import skuber.apps.v1.Deployment
import skuber.rbac._

import scala.concurrent.{ExecutionContext, Future}
import skuber.json.rbac.format._


class EventSourcedServiceConfigurationOperator(client: KubernetesClient)(implicit mat: Materializer, ec: ExecutionContext)
  extends AbstractOperator[EventSourcedServiceConfiguration.Status, EventSourcedServiceConfiguration.Resource](client) {

  import OperatorConstants._

  override protected def namespaces: List[String] = List("default")

  override protected def handleChanged(namespacedClient: KubernetesClient, resource: Resource) = {
    addOrUpdateDeployment(namespacedClient, resource)
  }

  override protected def handleDeleted(namespacedClient: KubernetesClient, resource: Resource): Future[Done] = {
    for {
      maybeExisting <- namespacedClient.getOption[Deployment](deploymentNameFor(resource.name))
      _ <- maybeExisting match {
        case Some(existing) if existing.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace) =>
          namespacedClient.delete[Deployment](existing.name)
        case None =>
          Future.successful(Done)
      }
      maybeExistingService <- namespacedClient.getOption[Service](resource.name)
      _ <- maybeExistingService match {
        case Some(existing) if existing.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace) =>
          namespacedClient.delete[Service](existing.name)
        case None =>
          Future.successful(Done)
      }
    } yield Done
  }

  override protected def statusFromError(error: Throwable, existing: Option[Resource]): EventSourcedServiceConfiguration.Status = {
    EventSourcedServiceConfiguration.Status(
      appliedSpecHash = None,
      journalConfigHash = None,
      appliedJournalConfigHash = None,
      reason = Some(s"Operator encountered unknown error: $error")
    )
  }

  override protected def hasAnythingChanged(resource: EventSourcedServiceConfiguration.Resource) = {
    (for {
      status <- resource.status
      appliedSpecHash <- status.appliedSpecHash
    } yield {
      val specHash = hashOf(resource.spec)
      if (specHash != appliedSpecHash) {
        true
      } else {
        // If there is no journalConfigHash, return false
        // If there is, return false if it matches the applied hash, otherwise return true
        status.journalConfigHash.exists(!status.appliedJournalConfigHash.contains(_))
      }
    }) getOrElse true
  }

  private def addOrUpdateDeployment(namespacedClient: KubernetesClient, resource: EventSourcedServiceConfiguration.Resource) = {
    for {
      maybeJournal <- namespacedClient.getOption[EventSourcedJournal.Resource](resource.spec.journal.name)
      status <- maybeJournal match {
        case Some(journal) if journal.status.isDefined =>
          addOrUpdateDeploymentForJournal(namespacedClient, resource, journal.name, journal.spec.`type`, journal.status.get)
        case _ =>
          handleDeleted(namespacedClient, resource).map(_ =>
            EventSourcedServiceConfiguration.Status(
              appliedSpecHash = Some(hashOf(resource.spec)),
              journalConfigHash = None,
              appliedJournalConfigHash = None,
              reason = Some("Journal '" + resource.spec.journal.name + "' not found in namespace '" + resource.namespace + "'.")
            )
          )
      }
    } yield Some(status)
  }

  private def addOrUpdateDeploymentForJournal(namespacedClient: KubernetesClient, resource: EventSourcedServiceConfiguration.Resource, journalName: String,
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
        _ <- ensureRbacPermissionsInNamespace(namespacedClient, resource.namespace)
        maybeExisting <- namespacedClient.getOption[Deployment](deploymentName)
        _ <- maybeExisting match {
          case Some(existing) =>
            namespacedClient.update(existing.copy(
              spec = deployment.spec,
              metadata = existing.metadata.copy(labels = deployment.metadata.labels)
            ))
          case None =>
            namespacedClient.create(deployment)
        }
        _ <- addOrUpdateService(namespacedClient, resource)
      } yield EventSourcedServiceConfiguration.Status(
        appliedSpecHash = Some(hashOf(resource.spec)),
        journalConfigHash = journal.specHash,
        appliedJournalConfigHash = journal.specHash,
        reason = None
      )
    }

    result.fold(
      error => Future.successful(EventSourcedServiceConfiguration.Status(
        appliedSpecHash = Some(hashOf(resource.spec)),
        journalConfigHash = None,
        appliedJournalConfigHash = None,
        reason = Some(error)
      )),
      identity
    )

  }

  private def addOrUpdateService(namespacedClient: KubernetesClient, resource: Resource): Future[Done] = {
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
      maybeExisting <- namespacedClient.getOption[Service](resource.name)
      _ <- maybeExisting match {
        case Some(existing) =>
          namespacedClient.update(existing.copy(
            spec = Some(existing.spec.fold(spec)(_.copy(
              ports = spec.ports,
              selector = spec.selector,
              _type = spec._type
            ))),
            metadata = existing.metadata.copy(labels = service.metadata.labels)
          ))
        case None =>
          namespacedClient.create(service)
      }
    } yield Done
  }

  private def ensureRbacPermissionsInNamespace(namespacedClient: KubernetesClient, namespace: String) = {
    for {
      _ <- ensurePodReaderRoleExists(namespacedClient, namespace)
      _ <- ensurePodReaderRoleBindingExists(namespacedClient, namespace)
    } yield ()
  }

  private def ensurePodReaderRoleExists(namespacedClient: KubernetesClient, namespace: String): Future[Role] = {
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

    namespacedClient.getOption[Role](PodReaderRoleName).flatMap {
      case Some(role) =>
        if (role.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace)) {
          // We manage it, check that it's up to date
          if (role.rules != expectedRole.rules) {
            println(s"Found existing managed role '$PodReaderRoleName' but does not match required config, updating...")
            namespacedClient.update(expectedRole)
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
        namespacedClient.create(expectedRole)
    }
  }

  private def ensurePodReaderRoleBindingExists(namespacedClient: KubernetesClient, namespace: String): Future[RoleBinding] = {
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

    namespacedClient.getOption[RoleBinding](PodReaderRoleBindingName).flatMap {
      case Some(roleBinding) =>
        if (roleBinding.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace)) {
          // We manage it, check that it's up to date
          if (roleBinding.roleRef != expectedRoleBinding.roleRef || roleBinding.subjects != expectedRoleBinding.subjects) {
            println(s"Found existing managed role binding '$PodReaderRoleBindingName' but does not match required config, updating...")
            namespacedClient.update(expectedRoleBinding)
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
        namespacedClient.create(expectedRoleBinding)
    }
  }

  private def deploymentNameFor(name: String) = "event-sourced-service-" + name

}