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

import akka.actor.ActorSystem
import akka.NotUsed
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
  private type Cache = Map[String, JsValueCustomResource]

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

        source.scanAsync(Map.empty[String, JsValueCustomResource]) { (cache, event) =>

          // It's unmodifed if the event type is modified but the object hasn't changed.
          // Otherwise, it's it's been modified in some way (eg, added, deleted or changed).
          val unmodified = event._type == EventType.MODIFIED &&
            cache.get(event._object.name).contains(event._object)

          if (unmodified) {
            Future.successful(cache)
          } else {
            val newCache = cache + (event._object.name -> event._object)
            // Attempt to parse
            Json.fromJson[Resource](Json.toJson(event._object)) match {
              case JsSuccess(resource, _) =>
                handleEvent(newCache, event._object, WatchEvent(event._type, resource))

              case err: JsError =>
                val status = operator.statusFromError(JsResult.Exception(err), None)
                updateStatus(cache, event._object, status)
            }
          }

        }
      }.runWith(Sink.ignore)
    }

    private def updateStatus(cache: Cache, resource: JsValueCustomResource, statusUpdate: operator.StatusUpdate): Future[Cache] = {
      statusUpdate match {
        case operator.StatusUpdate.None =>
          Future.successful(cache)
        case p@ operator.StatusUpdate.Patch(patch) =>
          implicit val patchWrites: Writes[p.PatchType] = p.writes
          client.patch[p.PatchType, JsValueCustomResource](resource.name, patch)
            .map(newResource => cache + (newResource.name -> newResource))
        case operator.StatusUpdate.Update(status) =>
          client.updateStatus(resource.withStatus(Json.toJson(status)))
            .map(newResource => cache + (newResource.name -> newResource))
      }
    }

    private def handleResource(cache: Cache, jsResource: JsValueCustomResource, resource: Resource): Future[Cache] = {
      operator.handleChanged(resource)
        .flatMap(statusUpdate => updateStatus(cache, jsResource, statusUpdate))
    }

    private def withErrorHandling(cache: Cache, jsResource: JsValueCustomResource, resource: Resource)(block: => Future[Cache]): Future[Cache] = {
      val result = try {
        block
      } catch {
        case NonFatal(e) => Future.failed(e)
      }
      result.recoverWith {
        case e =>
          println("Encountered performing operation on " + resource + ": " + e)
          e.printStackTrace()
          updateStatus(cache, jsResource, operator.statusFromError(e, Some(resource)))
      }
    }

    private def handleEvent(cache: Cache, jsResource: JsValueCustomResource, event: WatchEvent[Resource]): Future[Cache] = {
      println("Got event " + event)

      withErrorHandling(cache, jsResource, event._object) {
        event._type match {

          case EventType.ADDED =>
            handleResource(cache, jsResource, event._object)

          case EventType.DELETED =>
            operator.handleDeleted(event._object).map(_ =>
              cache - jsResource.name
            )

          case EventType.MODIFIED =>
            handleResource(cache, jsResource, event._object)

          case EventType.ERROR =>
            // We'll never get these because skuber doesn't even parse them successfully, just fail
            println("Got error: " + event)
            sys.error("Error event")
        }
      }
    }
  }

}