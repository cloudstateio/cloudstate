package io.cloudstate.proxy.eventing

import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorSystem, Cancellable, PoisonPill, Props}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.persistence.query.{EventEnvelope, Offset, Sequence, TimeBasedUUID}
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.{KillSwitches, Materializer}
import akka.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source}
import io.cloudstate.eventing
import com.google.protobuf.any.{Any => ProtoAny}

import scala.concurrent.duration._

class EventLogEventing(offsetTracking: OffsetTracking, eventsByTagQuery: EventsByTagQuery, system: ActorSystem)(
    implicit mat: Materializer
) extends EventingSupport {
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
          override type SourceEventRef = Offset

          override def run(flow: Flow[SourceEvent[Offset], Offset, _]): Cancellable = {
            val managerSettings = ClusterSingletonManagerSettings(system)

            val singleton = system.actorOf(
              ClusterSingletonManager.props(
                Props(new EventLogActor(consumerGroup, persistenceId, flow)),
                terminationMessage = PoisonPill,
                managerSettings
              ),
              actorName
            )

            new Cancellable {
              private val running = new AtomicBoolean()

              override def cancel(): Boolean =
                if (running.compareAndSet(false, true)) {
                  singleton ! PoisonPill
                  true
                } else {
                  false
                }

              override def isCancelled: Boolean = running.get()
            }
          }

        }

    }

  override def supportsDestination: Boolean = false

  override def createDestination(destination: eventing.EventDestination): EventDestination =
    throw new UnsupportedOperationException

  private class EventLogActor(consumerId: String, tag: String, flow: Flow[SourceEvent[Offset], Offset, _])
      extends Actor {

    import context.dispatcher

    private def createStream() =
      Source
        .fromFutureSource(
          offsetTracking
            .loadOffset(consumerId, tag)
            .map(offset => eventsByTagQuery.eventsByTag(tag, offset))
        )
        .map(transformEvent)
        .via(flow)
        .groupedWithin(20, 1.second)
        .mapAsync(1) { offsets =>
          offsetTracking.acknowledge(consumerId, tag, offsets.last)
        }

    private val runningStream =
      RestartSource
        .withBackoff(3.seconds, 30.seconds, 0.2)(createStream)
        .viaMat(KillSwitches.single)(Keep.right)
        .to(Sink.ignore)
        .run()

    private def transformEvent(event: EventEnvelope): SourceEvent[Offset] = {
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

      SourceEvent(cloudEvent, event.offset)
    }

    override def postStop(): Unit = runningStream.shutdown()

    override def receive: Receive = PartialFunction.empty
  }

  private def offsetToMessageId(offset: Offset) =
    offset match {
      case Sequence(seq) => seq.toString
      case TimeBasedUUID(uuid) => uuid.toString
      case other => throw new IllegalArgumentException(s"Unsupported offset: $other")
    }
}
