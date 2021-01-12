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

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

import akka.Done
import akka.actor.Cancellable
import akka.actor.typed.ActorSystem
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.query.{Offset, Sequence, TimeBasedUUID}
import akka.projection.{ProjectionBehavior, ProjectionContext, ProjectionId}
import akka.projection.scaladsl.{AtLeastOnceFlowProjection, SourceProvider}
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, FlowWithContext}
import io.cloudstate.eventing
import com.google.protobuf.any.{Any => ProtoAny}

import scala.concurrent.duration._

class EventLogEventing(projection: ProjectionSupport, readJournalPluginId: String, system: ActorSystem[_])(
    implicit mat: Materializer
) extends EventingSupport {

  private def sourceProvider(tag: String): SourceProvider[Offset, EventEnvelope[ProtoAny]] =
    EventSourcedProvider
      .eventsByTag[ProtoAny](system, readJournalPluginId = readJournalPluginId, tag = tag)

  override def name: String = "event-log"

  override def supportsSource: Boolean = true

  override def createSource(source: eventing.EventSource, serviceName: String): EventSource =
    source.source match {
      case eventing.EventSource.Source.EventLog(persistenceId) =>
        val consumerGroup = source.consumerGroup match {
          case "" => serviceName
          case cg => cg
        }

        val actorName = URLEncoder.encode(s"$persistenceId/$consumerGroup", "utf-8")

        new EventSource {
          override type SourceEventRef = ProjectionContext

          override def run(flow: Flow[SourceEvent[ProjectionContext], ProjectionContext, _]): Cancellable = {
            val projectionActor = ClusterSingleton(system)
              .init(SingletonActor(ProjectionBehavior(createProjection(consumerGroup, persistenceId, flow)), actorName))

            new Cancellable {
              private val cancelled = new AtomicBoolean()

              override def cancel(): Boolean =
                if (cancelled.compareAndSet(false, true)) {
                  projectionActor ! ProjectionBehavior.Stop
                  true
                } else {
                  false
                }

              override def isCancelled: Boolean = cancelled.get()
            }
          }

        }

    }

  override def supportsDestination: Boolean = false

  override def createDestination(destination: eventing.EventDestination): EventDestination =
    throw new UnsupportedOperationException

  private def createProjection(consumerId: String,
                               tag: String,
                               flow: Flow[SourceEvent[ProjectionContext], ProjectionContext, _]) =
    projection
      .create(
        ProjectionId(consumerId, tag),
        sourceProvider(tag),
        FlowWithContext[EventEnvelope[ProtoAny], ProjectionContext].via(
          Flow[(EventEnvelope[ProtoAny], ProjectionContext)]
            .map((transformEvent(tag) _).tupled)
            .via(flow)
            .map(ctx => Done -> ctx)
        )
      )
      .withSaveOffset(20, 1.second)
      .withRestartBackoff(3.seconds, 30.seconds, 0.2)

  private def transformEvent(tag: String)(event: EventEnvelope[ProtoAny],
                                          context: ProjectionContext): SourceEvent[ProjectionContext] = {
    val cloudEvent = event.event match {
      case ProtoAny(typeUrl, bytes, _) =>
        val entityId = event.persistenceId.dropWhile(_ != '|').tail
        CloudEvent(
          id = offsetToMessageId(event.offset),
          source = tag,
          specversion = "1.0",
          `type` = typeUrl,
          datacontenttype = "application/" + EventingManager.ProtobufAnyMediaSubType,
          dataschema = None,
          subject = Some(entityId),
          time = None,
          data = Some(bytes)
        )
      case other =>
        throw new IllegalStateException(s"Don't know how to handle event log message of type ${other.getClass}")
    }

    SourceEvent(cloudEvent, context)
  }

  private def offsetToMessageId(offset: Offset) =
    offset match {
      case Sequence(seq) => seq.toString
      case TimeBasedUUID(uuid) => uuid.toString
      case other => throw new IllegalArgumentException(s"Unsupported offset: $other")
    }
}
