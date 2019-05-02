package com.lightbend.statefulserverless.operator

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import play.api.libs.json._
import skuber.{CustomResource, HasStatusSubresource, ListResource, ResourceDefinition}
import skuber.api.client.{EventType, KubernetesClient, WatchEvent}
import skuber.json.format.ListResourceFormat

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import skuber._

class OperatorRunner(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext) {

  private val client = k8sInit

  def start[Status, Resource <: CustomResource[_, Status]](namespaces: List[String], operator: OperatorFactory[Status, Resource])
    (implicit fmt: Format[Resource], statusFmt: Format[Status], rd: ResourceDefinition[Resource], hs: HasStatusSubresource[Resource]): Unit = {

    namespaces.foreach { namespace =>
      val namespacedClient = client.usingNamespace(namespace)
      new NamespacedOperatorRunner[Status, Resource](namespacedClient, operator(namespacedClient)).start()
    }
  }

  private type JsValueCustomResource = CustomResource[JsValue, JsValue]
  private implicit val listResourceFormat: Format[ListResource[JsValueCustomResource]] = ListResourceFormat(implicitly[Format[JsValueCustomResource]])


  private class NamespacedOperatorRunner[Status, Resource <: CustomResource[_, Status]](client: KubernetesClient, operator: OperatorFactory[Status, Resource]#Operator)
    (implicit fmt: Format[Resource], statusFmt: Format[Status], rd: ResourceDefinition[Resource], hs: HasStatusSubresource[Resource], ec: ExecutionContext, mat: Materializer) {

    // See https://github.com/doriordan/skuber/issues/270
    // We do all watches and list resources using JsValue, rather than our actual classes, because this allows us
    // to handle parse errors
    implicit val jsValueRd: ResourceDefinition[JsValueCustomResource] = rd.asInstanceOf[ResourceDefinition[JsValueCustomResource]]
    implicit val statusSubEnabled: HasStatusSubresource[JsValueCustomResource] = CustomResource.statusMethodsEnabler[JsValueCustomResource]

    def start(): Unit = {
      // Summary of what we want our event loop to look like:
      // * We start by listing all the resources, and process them.
      // * Then we start watching from the resourceVersion that we got in our list, so we get all updates.
      // * But we also want to periodically recheck all resources, since sometimes there are race conditions
      //   between operators handling dependent resources (eg, if you deploy a journal and a service that uses
      //   it at the same time), so we only run the watch for a maximum of that time (eg, 5 minutes), before
      //   restarting.
      // * Also, if errors are encountered, we don't want to continually restart in a hot loop, so we use the
      //   RestartSource to restart with backoff.
      RestartSource.onFailuresWithBackoff(2.seconds, 20.seconds, 0.2) { () =>
        val source = Source.repeat(NotUsed)
          .flatMapConcat { _ =>
            Source.fromFutureSource(
              client.list[ListResource[JsValueCustomResource]]()
                .map { resources =>

                  val watch = Source.fromFutureSource(
                    client
                      .watchAll[JsValueCustomResource](sinceResourceVersion = Some(resources.resourceVersion))
                      // fromFutureSource doesn't like wildcard materailized values, change it to NotUsed
                      .map(_.mapMaterializedValue(_ => NotUsed))
                  )

                  Source(resources)
                    .map(WatchEvent(EventType.MODIFIED, _))
                    .concat(watch)
                }
            ).takeWithin(5.minutes)
          }

        source.mapAsync(1) { event =>
          val wasParseErrorBefore = (for {
            status <- event._object.status
            parseError <- (status \ "jsParseError").asOpt[Boolean]
          } yield parseError).getOrElse(false)

          Json.fromJson[Resource](Json.toJson(event._object)) match {
            case JsSuccess(resource, _) =>
              withErrorHandling(resource) {
                handleEvent(WatchEvent(event._type, resource))
              }

            case _: JsError if wasParseErrorBefore =>
              Future.successful(Done)

            case err: JsError =>
              val status = operator.statusFromError(JsResult.Exception(err), None)
              client.updateStatus(
                event._object.withStatus(Json.toJson(status).as[JsObject]
                  + ("jsParseError" -> JsBoolean(true))
              )).map(_ => Done)
          }

        }
      }.runWith(Sink.ignore)
    }

    private def updateStatus(resource: Resource, status: Status): Future[Done] = {
      client.updateStatus(resource.withStatus(status).asInstanceOf[Resource])
        .map(_ => Done)
    }

    private def handleResource(resource: Resource): Future[Done] = {
      if (operator.hasAnythingChanged(resource)) {
        operator.handleChanged(resource)
          .flatMap(_.fold(Future.successful(Done.done()))(status => updateStatus(resource, status)))
      } else {
        println(s"Nothing has changed for resource ${resource.name}")
        Future.successful(Done)
      }
    }

    private def withErrorHandling(resource: Resource)(block: => Future[Done]): Future[Done] = {
      val result = try {
        block
      } catch {
        case NonFatal(e) => Future.failed(e)
      }
      result.recoverWith {
        case e =>
          println("Encountered performing operation on " + resource + ": " + e)
          e.printStackTrace()
          updateStatus(resource, operator.statusFromError(e, Some(resource)))
      }
    }

    private def handleEvent(event: WatchEvent[Resource]): Future[Done] = {
      println("Got event " + event)

      withErrorHandling(event._object) {
        event._type match {

          case EventType.ADDED =>
            handleResource(event._object)

          case EventType.DELETED =>
            operator.handleDeleted(event._object)

          case EventType.MODIFIED =>
            handleResource(event._object)

          case EventType.ERROR =>
            // We'll never get these because skuber doesn't even parse them successfully, just fail
            println("Got error: " + event)
            sys.error("Error event")
        }
      }
    }
  }

}