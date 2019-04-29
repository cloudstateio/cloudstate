package com.lightbend.statefulserverless.operator

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import skuber.api.client.{EventType, KubernetesClient}
import skuber.apps.v1.Deployment
import skuber.rbac._
import skuber._
import skuber.LabelSelector.dsl._
//import skuber.json.format._
import skuber.json.rbac.format._

object EventSourcedServiceOperator {
  val OperatorNamespace = "statefulserverless.lightbend.com"
  val EventSourcedLabel = s"$OperatorNamespace/event-sourced"
  val JournalLabel = s"$OperatorNamespace/journal"

  val KubernetesManagedByLabel = "app.kubernetes.io/managed-by"

  val PodReaderRoleName = "statefulserverless-pod-reader"

  val PodReaderRoleBindingName = "statefulserverless-read-pods"


}

class EventSourcedServiceOperator(client: KubernetesClient)(implicit mat: Materializer, ec: ExecutionContext) {

  import EventSourcedServiceOperator._

  import EventSourcedService.eventSourcedServiceResourceDefinition

  def run(): Unit = {
    case class EventWithState()

    client.usingNamespace("default")
      .watchAllContinuously[EventSourcedService.Resource]()
      .scanAsync(Map.empty[String, EventSourcedService.Resource]) { (resources, event) =>
        println("Got event " + event)
        event._type match {
          case EventType.ADDED =>
            handleAdded(resources, event._object)
          case EventType.DELETED =>
            handleDeleted(resources, event._object)
          case EventType.MODIFIED =>
            handleModified(resources, event._object)
          case EventType.ERROR =>
            // Ignore?
            Future.successful(resources)
        }
      }.watchTermination()((_, done) => {
      done.onComplete(println)
    }).runWith(Sink.ignore)
  }

  private def handleAdded(resources: Map[String, EventSourcedService.Resource], resource: EventSourcedService.Resource) = {
    for {
      _ <- ensureRbacPermissionsInNamespace(resource.metadata.namespace)
      newResource <- addDeployment(resource)
    } yield {
      resources + (newResource.name -> newResource)
    }
  }

  private def handleModified(resources: Map[String, EventSourcedService.Resource], resource: EventSourcedService.Resource) = {
    // For now, just pretend it was added, and let the deployment do the change detection
    handleAdded(resources, resource)
  }

  private def handleDeleted(resources: Map[String, EventSourcedService.Resource], resource: EventSourcedService.Resource) = {
    val namespaced = client.usingNamespace(resource.namespace)
    namespaced.getOption[Deployment](deploymentNameFor(resource.name)).flatMap {
      case Some(existing) if existing.metadata.labels.get(KubernetesManagedByLabel).contains(OperatorNamespace) =>
        namespaced.delete[Deployment](existing.name)
      case Some(existing) =>
        println("Event sourced function " + resource.name + " deleted but the deployment wasn't managed by us, not deleting.")
        Future.successful(())
      case None =>
        println("Event sourced function " + resource.name + " deleted but no deployment found to delete.")
        Future.successful(())
    }.map { _ =>
      resources - resource.name
    }
  }

  private def addDeployment(resource: EventSourcedService.Resource): Future[EventSourcedService.Resource] = {
    client.getOption[EventSourcedJournal.Resource](resource.spec.journal.name).flatMap {
      case Some(journal) =>
        addDeploymentForJournal(resource, journal).map(_ => resource)
      case None =>
        // Todo set status to error
        println(s"No journal found for ${resource.spec.journal}")
        Future.successful(resource)
    }
  }

  private def addDeploymentForJournal(resource: EventSourcedService.Resource, journal: EventSourcedJournal.Resource) = {
    val templateSpec = resource.spec.template.spec

    // todo handle errors appropriately
    val keyspace = resource.spec.journal.config.flatMap(obj => (obj \ "keyspace").asOpt[String]).getOrElse(sys.error("No Cassandra keyspace!"))

    // todo this should come from the validated journal status, and handle errors appropriately
    val contactPoints = (journal.spec.config.getOrElse(JsObject.empty) \ "service").as[String]

    val injectedSpec = Pod.Spec(
      containers = templateSpec.containers.map {
        case container if container.name == "" => container.copy(name = resource.name)
        case container => container
      })
      .addContainer(
        Container(
          name = "akka-sidecar",
          // todo this should come from the journal status
          image = "gcr.io/stateserv/stateful-serverless-backend-cassandra:latest",
          ports = List(
            Container.Port(containerPort = 9000, name = "grpc"),
            Container.Port(containerPort = 8558, name = "management")
          ),
          env = List(
            EnvVar("SELECTOR_LABEL_VALUE", resource.name),
            EnvVar("SELECTOR_LABEL", EventSourcedLabel),
            EnvVar("REQUIRED_CONTACT_POINT_NR", Math.ceil(resource.spec.replicas / 2 + 1).toString),
            // todo where does this come from?
            EnvVar("JAVA_OPTS", "-Xms256m -Xmx256m"),
            EnvVar("CASSANDRA_KEYSPACE", keyspace),
            EnvVar("CASSANDRA_CONTACT_POINTS", contactPoints)
          ),
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

    // Create the deployment
    val deployment = Deployment(
      metadata = ObjectMeta(
        name = deploymentNameFor(resource.name),
        labels = Map(
          KubernetesManagedByLabel -> OperatorNamespace,
          EventSourcedLabel -> resource.name,
          JournalLabel -> journal.name
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
            JournalLabel -> journal.name
          )
        ),
        spec = Some(injectedSpec)
      ))


    client.create(deployment)
  }

  private def ensureRbacPermissionsInNamespace(namespace: String) = {
    for {
      _ <- ensurePodReaderRoleExists(namespace)
      _ <- ensurePodReaderRoleBindingExists(namespace)
    } yield ()
  }

  private def ensurePodReaderRoleExists(namespace: String): Future[Role] = {
    val namespacedClient = client.usingNamespace(namespace)
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
    val namespacedClient = client.usingNamespace(namespace)

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