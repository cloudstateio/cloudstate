package com.lightbend.statefulserverless.operator

import akka.Done
import akka.stream.Materializer
import com.lightbend.statefulserverless.operator.EventSourcedService.Resource

import scala.concurrent.{ExecutionContext, Future}
import skuber.api.client.KubernetesClient
import skuber._

import EventSourcedService.eventSourcedServiceResourceDefinition

class EventSourcedServiceOperator(client: KubernetesClient)(implicit mat: Materializer, ec: ExecutionContext) extends
  AbstractOperator[EventSourcedService.Status, EventSourcedService.Resource](client) {

  import OperatorConstants._


  override protected def namespaces: List[String] = List("default")

  override protected def hasAnythingChanged(resource: Resource): Boolean = {
    (for {
      status <- resource.status
      appliedSpecHash <- status.appliedSpecHash
    } yield appliedSpecHash != hashOf(resource.spec)) getOrElse true
  }

  override protected def handleChanged(namespacedClient: K8SRequestContext, resource: Resource): Future[EventSourcedService.Status] = {
    val newSpec = EventSourcedServiceConfiguration.Spec(
      replicas = resource.spec.replicas,
      journal = resource.spec.journal,
      template = resource.spec.template
    )
    val labels = Seq(
      KubernetesManagedByLabel -> OperatorNamespace,
      EventSourcedLabel -> resource.name,
      JournalLabel -> resource.spec.journal.name
    )
    for {
      maybeConfig <- namespacedClient.getOption[EventSourcedServiceConfiguration.Resource](resource.name)
      _ <- maybeConfig match {
        case Some(oldConfig) =>
          namespacedClient.update(oldConfig.copy(spec = newSpec).withLabels(labels: _*))
        case None =>
          namespacedClient.create(EventSourcedServiceConfiguration(resource.name, newSpec).withLabels(labels: _*))
      }
    } yield EventSourcedService.Status(
      appliedSpecHash = Some(hashOf(resource.spec)),
      reason = None
    )
  }

  override protected def handleDeleted(namespacedClient: K8SRequestContext, resource: Resource): Future[Done] = {
    for {
      maybeConfig <- namespacedClient.getOption[EventSourcedServiceConfiguration.Resource](resource.name)
      _ <- maybeConfig match {
        case Some(_) =>
          namespacedClient.delete[EventSourcedServiceConfiguration.Resource](resource.name)
        case None =>
          Future.successful(Done)
      }
    } yield Done
  }

  override protected def statusFromError(error: Throwable, existing: Option[Resource]): EventSourcedService.Status = {
    EventSourcedService.Status(
      appliedSpecHash = None,
      reason = Some(s"Unknown operator error: $error")
    )
  }

}