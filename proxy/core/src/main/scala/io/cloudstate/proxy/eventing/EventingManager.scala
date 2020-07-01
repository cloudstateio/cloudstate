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

package io.cloudstate.proxy.eventing

import akka.{Done, NotUsed}
import akka.actor.Cancellable
import akka.stream.{FlowShape, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, RunnableGraph, Sink, Source}
import io.cloudstate.protocol.entity.{ClientAction, EntityDiscoveryClient, Failure, Reply, UserFunctionError}
import io.cloudstate.proxy.{Serve, UserFunctionRouter}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.protobuf.{Options, Types}
import io.cloudstate.proxy.entity.UserFunctionReply
import io.cloudstate.eventing.{Eventing, EventingProto}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.MethodDescriptor

import scala.collection.JavaConverters._
import scala.concurrent.Future
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import Serve.CommandHandler

trait Emitter {
  def emit(payload: ProtobufAny, method: MethodDescriptor): Boolean
}

object Emitters {
  val ignore: Emitter = new Emitter {
    override def emit(payload: ProtobufAny, method: MethodDescriptor): Boolean = false
  }
}

/* Commented out temporarily while projection support is developed

trait EventingSupport {
def createSource(sourceName: String, handler: CommandHandler): Source[UserFunctionCommand, Future[Cancellable]]
def createDestination(destinationName: String, handler: CommandHandler): Flow[ProtobufAny, AnyRef, NotUsed]
}

object EventingManager {

final val log = LoggerFactory.getLogger("EventingManager")

final case class EventMapping private (entity: ServableEntity, routes: Map[MethodDescriptor, Eventing])

private val noEmitter = Future.successful(Emitters.ignore)

// Contains all entities which has at least one endpoint which accepts events
def createEventMappings(entities: Seq[ServableEntity]): Seq[EventMapping] =
  entities.flatMap { entity =>
    val endpoints =
      entity.serviceDescriptor.getMethods.iterator.asScala.foldLeft(Map.empty[MethodDescriptor, Eventing]) {
        case (map, method) =>
          EventingProto.eventing.get(Options.convertMethodOptions(method)) match {
            case None => map
            case Some(e) =>
              (e.in, e.out) match {
                case (null, null) | ("", "") => map
                case (in, out) if in == out =>
                  throw new IllegalStateException(
                    s"Endpoint '${method.getFullName}' has the same input topic as output topic ('${in}'), this is not allowed."
                  )
                case (in, out) =>
                  log.debug("EventingProto.events for {}: {} -> {}",
                            method.getFullName: AnyRef,
                            in: AnyRef,
                            out: AnyRef)
                  map.updated(method, e)
              }
          }
      }

    if (endpoints.isEmpty) Nil
    else List(EventMapping(entity, endpoints))
  }

def createSupport(eventConfig: Config)(implicit materializer: Materializer): Option[EventingSupport] =
  eventConfig.getString("support") match {
    case "none" =>
      log.info("Eventing support turned off in configuration")
      None
    case s @ "google-pubsub" =>
      log.info("Creating google-pubsub eventing support")
      Some(new GCPubsubEventingSupport(eventConfig.getConfig(s), materializer))
    case other =>
      throw new IllegalStateException(s"Check your configuration. There is no eventing support named: $other")
  }

def createStreams(router: UserFunctionRouter,
                  entityDiscoveryClient: EntityDiscoveryClient,
                  entities: Seq[ServableEntity],
                  support: EventingSupport): Option[RunnableGraph[(Emitter, Future[Done])]] =
  createEventMappings(entities) match {
    case Nil => None
    case eventMappings =>
      val allEligibleOutputsByMethodDescriptor =
        eventMappings
          .flatMap(_.routes.collect {
            case (m, e) if e.out != "" => (m.getFullName, e.out)
          })
          .toMap

      type TopicName = String
      type Record = (TopicName, ProtobufAny)

      val inNOutBurger: Seq[
        (Option[(TopicName, Source[Record, Future[Cancellable]])], Option[(TopicName, Flow[Record, AnyRef, NotUsed])])
      ] =
        for {
          EventMapping(entity, routes) <- eventMappings
          (mdesc, eventing) <- routes.toSeq // Important since we do not want dedupe that we get from the map otherwise
        } yield {
          log.info("Creating route for {}", eventing)
          val commandHandler = new CommandHandler(entity, mdesc, router, noEmitter, entityDiscoveryClient, log) // Could we reuse these from Serve?

          val in = Option(eventing.in).collect({
            case topic if topic != "" =>
              val source =
                support
                  .createSource(topic, commandHandler)
                  .via(commandHandler.flow)
                  .collect({ case any if eventing.out != "" => (eventing.out, any) }) //Without an out there is nothing to persist
              (topic, source)
          })

          val out = Option(eventing.out).collect({
            case topic if topic != "" =>
              val dest = Flow[Record]
                .map(_._2)
                .via(support.createDestination(topic, commandHandler))
                .dropWhile(_ => true)
              (topic, dest)
          })

          (in, out)
        }

      val sources = inNOutBurger.collect({ case (Some(in), _) => in })

      val deadLetters =
        Flow[Record]
          .map({
            case (topic, msg) =>
              log.warn(
                s"Message destined for eventing topic '${topic}' discarded since no such topic is found in the configuration."
              )
              msg
          })
          .dropWhile(_ => true)

      val destinations =
        ("", deadLetters) +: inNOutBurger.collect({ case (_, Some(out)) => out })

      val emitter =
        Source.queue[Record](destinations.size * 128, OverflowStrategy.backpressure)

      val destinationMap = destinations.map(_._1).sorted.zipWithIndex.toMap
      val destinationSelector =
        (r: Record) => destinationMap.get(r._1).getOrElse(destinationMap("")) // Send to deadLetters if no match

      val eventingFlow = Flow
        .fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val mergeForPublish = b.add(Merge[Record](sources.size + 1)) // 1 + for emitter

          val routeToDestination =
            b.add(Partition[Record](destinations.size, destinationSelector))

          val mergeForExit = b.add(Merge[AnyRef](destinations.size))

          sources.zipWithIndex foreach {
            case ((topicName, source), idx) =>
              b.add(source).out ~> mergeForPublish.in(idx + 1) // 0 we keep for emitter
          }

          mergeForPublish.out ~> routeToDestination.in

          destinations foreach {
            case (topicName, flow) =>
              routeToDestination.out(destinationMap(topicName)) ~> b.add(flow) ~> mergeForExit
          }

          FlowShape(mergeForPublish.in(0), mergeForExit.out)
        })

      Some(
        emitter
          .via(eventingFlow)
          .toMat(Sink.ignore)((queue, future) => {
            val emitter = new Emitter {
              override def emit(event: ProtobufAny, method: MethodDescriptor): Boolean =
                if (event.value.isEmpty) false
                else {
                  allEligibleOutputsByMethodDescriptor
                    .get(method.getFullName) // FIXME Check expected type of event compared to method.getOutputType
                    .map(out => queue.offer((out, event))) // FIXME handle this Future
                    .isDefined
                }
            }
            (emitter, future)
          })
      )
  }
}

 */
