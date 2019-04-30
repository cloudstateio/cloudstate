package com.lightbend.statefulserverless.operator

import akka.Done
import akka.stream.Materializer
import com.lightbend.statefulserverless.operator.EventSourcedJournal.Resource
import play.api.libs.json.JsObject
import skuber.LabelSelector.dsl._
import skuber._
import skuber.api.client.KubernetesClient

import scala.concurrent.{ExecutionContext, Future}

import EventSourcedJournal.eventSourcedJournalResourceDefinition

class EventSourcedJournalOperator(client: KubernetesClient)(implicit mat: Materializer, ec: ExecutionContext) extends
  AbstractOperator[EventSourcedJournal.Status, EventSourcedJournal.Resource](client) {

  import OperatorConstants._


  override protected def namespaces: List[String] = List("default")

  override protected def hasAnythingChanged(resource: Resource): Boolean = {
    (for {
      status <- resource.status
      specHash <- status.specHash
    } yield {
      hashOf(resource.spec) != specHash
    }).getOrElse(true)
  }

  override protected def handleAdded(namespacedClient: K8SRequestContext, resource: Resource): Future[EventSourcedJournal.Status] = {
    handleModified(namespacedClient, resource)
  }

  private def errorStatus(reason: String) = EventSourcedJournal.Status(
    specHash = None,
    image = None,
    sidecarEnv = None,
    reason = Some(reason)
  )

  override protected def handleModified(namespacedClient: K8SRequestContext, resource: Resource): Future[EventSourcedJournal.Status] = {
    val status = resource.spec.`type` match {
      case Some("cassandra") =>
        resource.spec.deployment match {
          case Some("unmanaged") =>
            (resource.spec.config.getOrElse(JsObject.empty) \ "service").asOpt[String] match {
              case Some(contactPoints) =>
                EventSourcedJournal.Status(
                  specHash = Some(hashOf(resource.spec)),
                  image = Some("gcr.io/stateserv/stateful-serverless-backend-cassandra:latest"),
                  sidecarEnv = Some(List(
                    EnvVar("CASSANDRA_CONTACT_POINTS", contactPoints)
                  )),
                  reason = None
                )
            }
          case Some(unknown) => errorStatus(s"Unknown Cassandra deployment type: $unknown")
          case None => errorStatus(s"No deployment type specified")
        }
      case Some(unknown) => errorStatus(s"Unknown journal type: $unknown")
      case None => errorStatus("No journal type specified")
    }

    if (status.reason.isEmpty && status.specHash.isDefined) {
      updateDependents(namespacedClient, resource.name, _.copy(journalConfigHash = status.specHash))
        .map(_ => status)
    } else {
      Future.successful(status)
    }
  }

  override protected def handleDeleted(namespacedClient: K8SRequestContext, resource: Resource): Future[Done] = {
    updateDependents(namespacedClient, resource.name, _.copy(journalConfigHash = None))
  }

  private def updateDependents(namespacedClient: KubernetesClient, name: String,
    update: EventSourcedServiceConfiguration.Status => EventSourcedServiceConfiguration.Status) = {

    import EventSourcedServiceConfiguration.eventSourcedServiceConfigurationResourceDefinition

    (for {
      serviceConfigs <- namespacedClient.listSelected[EventSourcedServiceConfiguration.ResourceList](LabelSelector(
        JournalLabel is name
      ))
      _ <- Future.sequence(serviceConfigs.map { serviceConfig =>
        val status = serviceConfig.status.getOrElse(
          EventSourcedServiceConfiguration.Status(None, None, None, None)
        )
        namespacedClient.updateStatus(serviceConfig.withStatus(update(status)))
      })
    } yield Done).recover {
      case error =>
        println("Error while attempting to update dependent service configuration resource, ignoring")
        error.printStackTrace()
        Done
    }
  }

  override protected def statusFromError(error: Throwable, existing: Resource): EventSourcedJournal.Status = {
    errorStatus("Unknown operator error: " + error)
  }
}