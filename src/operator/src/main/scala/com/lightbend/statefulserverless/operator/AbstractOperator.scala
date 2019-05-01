package com.lightbend.statefulserverless.operator

import java.security.MessageDigest
import java.util.Base64

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import com.lightbend.statefulserverless.operator.EventSourcedJournal.Resource
import play.api.libs.json._
import skuber.{CustomResource, HasStatusSubresource, ListResource, ResourceDefinition}
import skuber.api.client.{EventType, KubernetesClient}
import skuber.json.format._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

abstract class AbstractOperator[Status, Resource <: CustomResource[_, Status]](client: KubernetesClient)(implicit mat: Materializer,
  ec: ExecutionContext, fmt: Format[Resource], statusFmt: Format[Status], rd: ResourceDefinition[Resource], hs: HasStatusSubresource[Resource]) {

  // See https://github.com/doriordan/skuber/issues/270
  // We do all watches and list resources using JsValue, rather than our actual classes, because this allows us
  // to handle parse errors
  type JsValueCustomResource = CustomResource[JsValue, JsValue]
  private implicit val listResourceFormat: Format[ListResource[JsValueCustomResource]] = ListResourceFormat(implicitly[Format[JsValueCustomResource]])
  private implicit val jsValueRd: ResourceDefinition[JsValueCustomResource] = rd.asInstanceOf[ResourceDefinition[JsValueCustomResource]]
  private implicit val statusSubEnabled: HasStatusSubresource[JsValueCustomResource] = CustomResource.statusMethodsEnabler[JsValueCustomResource]

  protected def namespaces: List[String]

  protected def hasAnythingChanged(resource: Resource): Boolean

  protected def handleChanged(namespacedClient: KubernetesClient, resource: Resource): Future[Option[Status]]

  protected def handleDeleted(namespacedClient: KubernetesClient, resource: Resource): Future[Done]

  protected def statusFromError(error: Throwable, existing: Option[Resource] = None): Status

  protected def updateStatus(resource: Resource, status: Status): Future[Done] = {
    client.updateStatus(resource.withStatus(status).asInstanceOf[Resource])
      .map(_ => Done)
  }


  protected def hashOf(obj: Any) = {
    val md = MessageDigest.getInstance("MD5")
    Base64.getEncoder.encodeToString(md.digest(obj.toString.getBytes("utf-8")))
  }

  def run(): Unit = {
    namespaces.foreach(namespace => {
      val namespacedClient = client.usingNamespace(namespace)

      RestartSource.onFailuresWithBackoff(2.seconds, 20.seconds, 0.2) { () =>
        val futureSource = namespacedClient.list[ListResource[JsValueCustomResource]]().flatMap { resources =>
          resources.foldLeft(Future.successful(Done.done())) { (previousFuture, jsValueResource) =>
            previousFuture.flatMap { _ =>
              Json.fromJson[Resource](Json.toJson(jsValueResource)) match {
                case JsSuccess(resource, _) =>
                  withErrorHandling(namespacedClient, resource) {
                    handleResource(namespacedClient, "discovered", resource)
                  }
                case err: JsError =>
                  val status = statusFromError(JsResult.Exception(err), None)
                  client.updateStatus(jsValueResource.withStatus(Json.toJson(status)))
                    .map(_ => Done)
              }
            }
          }.map { _ =>
            startWatching(namespacedClient, resources.resourceVersion)
          }
        }

        Source.fromFutureSource(futureSource)

      }.runWith(Sink.ignore)
    })
  }

  private def handleResource(namespacedClient: KubernetesClient, event: String, resource: Resource): Future[Done] = {
    if (hasAnythingChanged(resource)) {
      handleChanged(namespacedClient, resource)
        .flatMap(_.fold(Future.successful(Done.done()))(status => updateStatus(resource, status)))
    } else {
      println(s"Nothing has changed for $event resource ${resource.name}")
      Future.successful(Done)
    }
  }

  private def withErrorHandling(namespacedClient: KubernetesClient, resource: Resource)(block: => Future[Done]): Future[Done] = {
    val result = try {
      block
    } catch {
      case NonFatal(e) => Future.failed(e)
    }
    result.recoverWith {
      case e =>
        println("Encountered performing operation on " + resource + ": " + e)
        e.printStackTrace()
        updateStatus(resource, statusFromError(e, Some(resource)))
    }
  }

  private def startWatching(namespacedClient: KubernetesClient, sinceResourceVersion: String): Source[_, _] = {
    namespacedClient
      .watchAllContinuously[Resource](
        sinceResourceVersion = Some(sinceResourceVersion)
      ).mapAsync(1) { event =>

        println("Got event " + event)

        withErrorHandling(namespacedClient, event._object) {
          event._type match {

            case EventType.ADDED =>
              handleResource(namespacedClient, "added", event._object)

            case EventType.DELETED =>
              handleDeleted(namespacedClient, event._object)

            case EventType.MODIFIED =>
              handleResource(namespacedClient, "modified", event._object)

            case EventType.ERROR =>
              // We'll never get these because skuber doesn't even parse them successfully, just fail
              println("Got error: " + event)
              sys.error("Error event")
          }
        }
      }
  }

}
