package io.cloudstate.operator

import akka.{Done, NotUsed}
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source}
import play.api.libs.json.Format
import skuber.{ListResource, ObjectResource, ResourceDefinition}
import skuber.api.client.{EventType, KubernetesClient, WatchEvent}

import scala.concurrent.duration._
import skuber.json.format._

import scala.concurrent.ExecutionContext

object Watcher {

  private implicit def listResourceFormat[Resource <: ObjectResource: Format]: Format[ListResource[Resource]] = ListResourceFormat(implicitly[Format[Resource]])

  def watch[Resource <: ObjectResource: Format: ResourceDefinition](client: KubernetesClient,
      handler: Flow[WatchEvent[Resource], _, _])(implicit ec: ExecutionContext, mat: Materializer): KillSwitch = {

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
            client.list[ListResource[Resource]]()
              .map { resources =>
                val watch = client
                  .watchAllContinuously[Resource](sinceResourceVersion = Some(resources.resourceVersion))

                Source(resources)
                  .map(WatchEvent(EventType.MODIFIED, _))
                  .concat(watch)
              }
          ).takeWithin(5.minutes)
        }

      source.via(handler)
    }.viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore).run()
  }

  def watchSingle[Resource <: ObjectResource: Format: ResourceDefinition](client: KubernetesClient, resourceName: String,
       handler: Flow[WatchEvent[Resource], _, _])(implicit ec: ExecutionContext, mat: Materializer): KillSwitch = {

    RestartSource.onFailuresWithBackoff(2.seconds, 20.seconds, 0.2) { () =>
      val source = Source.repeat(NotUsed)
        .flatMapConcat { _ =>
          Source.fromFutureSource(
            client.getOption[Resource](resourceName).map {
                case Some(resource) =>
                  val watch = client.watchContinuously[Resource](resourceName, sinceResourceVersion = Some(resource.resourceVersion))
                  Source.single(resource)
                    .map(WatchEvent(EventType.MODIFIED, _))
                    .concat(watch)
                case None =>
                  throw new RuntimeException(s"Resource $resourceName not found in namespace ${client.namespaceName}!")
              }
          ).takeWithin(5.minutes)
        }

      source.via(handler)
    }.viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore).run()
  }
}
