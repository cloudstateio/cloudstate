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

  private def errorStatus(reason: String) = EventSourcedJournal.Status(
    specHash = None,
    image = None,
    sidecarEnv = None,
    reason = Some(reason)
  )

  override protected def handleChanged(namespacedClient: K8SRequestContext, resource: Resource): Future[Option[EventSourcedJournal.Status]] = {
    val status = resource.spec.`type` match {
      case `CassandraJournalType` =>
        resource.spec.deployment match {
          case `UnmanagedJournalDeployment` =>
            (resource.spec.config \ "service").asOpt[String] match {
              case Some(contactPoints) =>
                EventSourcedJournal.Status(
                  specHash = Some(hashOf(resource.spec)),
                  image = Some("gcr.io/stateserv/stateful-serverless-backend-cassandra:latest"),
                  sidecarEnv = Some(List(
                    EnvVar("CASSANDRA_CONTACT_POINTS", contactPoints)
                  )),
                  reason = None
                )
              case None => errorStatus("No service name declared in unmanaged Cassandra journal")
            }
          case unknown => errorStatus(s"Unknown Cassandra deployment type: $unknown")
        }
      case unknown => errorStatus(s"Unknown journal type: $unknown")
    }

    if (status.reason.isEmpty && status.specHash.isDefined) {
      // We have to first update our own status before we update our dependents, since they depend on our updated status
      for {
        _ <- updateStatus(resource, status)
        _ <- updateDependents(namespacedClient, resource.name, _.copy(journalConfigHash = status.specHash))
      } yield None
    } else {
      Future.successful(Some(status))
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

  override protected def statusFromError(error: Throwable, existing: Option[Resource]): EventSourcedJournal.Status = {
    errorStatus("Unknown operator error: " + error)
  }
}