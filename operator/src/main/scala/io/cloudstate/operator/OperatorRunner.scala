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

package io.cloudstate.operator

import akka.stream.{KillSwitch, Materializer}
import akka.stream.scaladsl.Flow
import play.api.libs.json._
import skuber.{CustomResource, HasStatusSubresource, ListResource, ResourceDefinition}
import skuber.api.client.{EventType, K8SException, KubernetesClient, WatchEvent}
import skuber.json.format.ListResourceFormat

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import skuber._

class OperatorRunner[Status, Resource <: CustomResource[_, Status]](client: KubernetesClient,
                                                                    operator: OperatorFactory[Status, Resource])(
    implicit fmt: Format[Resource],
    statusFmt: Format[Status],
    rd: ResourceDefinition[Resource],
    hs: HasStatusSubresource[Resource],
    mat: Materializer,
    ec: ExecutionContext
) {

  def start(namespace: String, config: OperatorConfig): KillSwitch = {
    val namespacedClient = client.usingNamespace(namespace)
    new NamespacedOperatorRunner(namespacedClient, operator(namespacedClient, config)).start()
  }

  private type JsValueCustomResource = CustomResource[JsValue, JsValue]
  private type Cache = Map[String, JsValueCustomResource]

  private implicit val listResourceFormat: Format[ListResource[JsValueCustomResource]] = ListResourceFormat(
    implicitly[Format[JsValueCustomResource]]
  )

  private class NamespacedOperatorRunner(client: KubernetesClient,
                                         operator: OperatorFactory[Status, Resource]#Operator) {

    // See https://github.com/doriordan/skuber/issues/270
    // We do all watches and list resources using JsValue, rather than our actual classes, because this allows us
    // to handle parse errors
    implicit val jsValueRd: ResourceDefinition[JsValueCustomResource] =
      rd.asInstanceOf[ResourceDefinition[JsValueCustomResource]]
    implicit val statusSubEnabled: HasStatusSubresource[JsValueCustomResource] =
      CustomResource.statusMethodsEnabler[JsValueCustomResource]

    def start(): KillSwitch =
      Watcher.watch[JsValueCustomResource](
        client,
        Flow[WatchEvent[JsValueCustomResource]]
          .scanAsync(Map.empty[String, JsValueCustomResource]) { (cache, event) =>
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
      )

    private def updateStatus(cache: Cache,
                             resource: JsValueCustomResource,
                             statusUpdate: operator.StatusUpdate): Future[Cache] =
      statusUpdate match {
        case operator.StatusUpdate.None =>
          Future.successful(cache)
        case p @ operator.StatusUpdate.Patch(patch) =>
          implicit val patchWrites: Writes[p.PatchType] = p.writes
          client
            .patch[p.PatchType, JsValueCustomResource](resource.name, patch)
            .map(newResource => cache + (newResource.name -> newResource))
        case operator.StatusUpdate.Update(status) =>
          client
            .updateStatus(resource.withStatus(Json.toJson(status)))
            .map(newResource => cache + (newResource.name -> newResource))
            .recover {
              case e: K8SException if e.status.code.contains(409) =>
                // Something else has modified it, so ignore, because this operator should get notified of the new resource
                println(
                  s"Got conflict on updating status of ${resource.namespace}/${resource.name}:${resource.uid}@${resource.metadata.resourceVersion}, ignoring to handle changed version."
                )
                cache
            }
      }

    private def handleResource(cache: Cache, jsResource: JsValueCustomResource, resource: Resource): Future[Cache] =
      operator
        .handleChanged(resource)
        .flatMap(statusUpdate => updateStatus(cache, jsResource, statusUpdate))

    private def withErrorHandling(cache: Cache, jsResource: JsValueCustomResource, resource: Resource)(
        block: => Future[Cache]
    ): Future[Cache] = {
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

    private def handleEvent(cache: Cache,
                            jsResource: JsValueCustomResource,
                            event: WatchEvent[Resource]): Future[Cache] = {
      println("Got event " + event)

      withErrorHandling(cache, jsResource, event._object) {
        event._type match {

          case EventType.ADDED =>
            handleResource(cache, jsResource, event._object)

          case EventType.DELETED =>
            operator.handleDeleted(event._object).map(_ => cache - jsResource.name)

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
