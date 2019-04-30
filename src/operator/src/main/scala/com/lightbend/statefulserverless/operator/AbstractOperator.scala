package com.lightbend.statefulserverless.operator

import java.security.MessageDigest
import java.util.Base64

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{RestartSource, Sink}
import play.api.libs.json.Format
import skuber.{CustomResource, HasStatusSubresource, ResourceDefinition}
import skuber.api.client.{EventType, KubernetesClient}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

abstract class AbstractOperator[Status, Resource <: CustomResource[_, Status]](client: KubernetesClient)(implicit mat: Materializer,
  ec: ExecutionContext, fmt: Format[Resource], rd: ResourceDefinition[Resource], hs: HasStatusSubresource[Resource]) {

  protected abstract def namespaces: List[String]
  protected abstract def hasAnythingChanged(resource: Resource): Boolean
  protected abstract def handleAdded(namespacedClient: KubernetesClient, resource: Resource): Future[Status]
  protected abstract def handleModified(namespacedClient: KubernetesClient, resource: Resource): Future[Status]
  protected abstract def handleDeleted(namespacedClient: KubernetesClient, resource: Resource): Future[Done]
  protected abstract def statusFromError(error: Throwable, existing: Resource): Status

  protected def updateStatus(resource: Resource, status: Status): Future[Done] = {
    client.updateStatus(resource.withStatus(status).asInstanceOf[Resource])
      .map(_ => Done)
  }

  private def _updateStatus(resource: Resource)(status: Status): Future[Done] = updateStatus(resource, status)


  protected def hashOf(obj: Any) = {
    val md = MessageDigest.getInstance("MD5")
    Base64.getEncoder.encodeToString(md.digest(obj.toString.getBytes("utf-8")))
  }

  def run(): Unit = {
    namespaces.foreach(namespace => {
      RestartSource.onFailuresWithBackoff(2.seconds, 20.seconds, 0.2) { () =>
        val namespacedClient = client.usingNamespace(namespace)

        namespacedClient
          .watchAllContinuously[Resource]()
          .mapAsync(1) { event =>

            println("Got event " + event)

            val result = try {
              event._type match {

                case EventType.ADDED =>
                  if (hasAnythingChanged(event._object)) {
                    handleAdded(namespacedClient, event._object)
                      .flatMap(_updateStatus(event._object))
                  } else {
                    println("Nothing has changed for added resource " + event._object.name)
                    Future.successful(Done)
                  }

                case EventType.DELETED =>
                  handleDeleted(namespacedClient, event._object)

                case EventType.MODIFIED =>
                  if (hasAnythingChanged(event._object)) {
                    handleModified(namespacedClient, event._object)
                      .flatMap(_updateStatus(event._object))
                  } else {
                    println("Nothing has changed for modified resource " + event._object.name)
                    Future.successful(Done)
                  }

                case EventType.ERROR =>
                  // When do we get this?
                  println("Got error: " + event)
                  Future.successful(Done)
              }
            } catch {
              case NonFatal(e) => Future.failed(e)
            }

            result.recoverWith {
              case e =>
                println("Encountered error handling " + event + ": " + e)
                e.printStackTrace()
                updateStatus(event._object, statusFromError(e, event._object))
            }
          }
      }.runWith(Sink.ignore)
    })
  }

}
