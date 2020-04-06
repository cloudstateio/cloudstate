package io.cloudstate.proxy.eventing

import java.time.Instant

import akka.actor.Cancellable
import akka.{Done, NotUsed}
import akka.http.scaladsl.model.HttpEntity
import akka.stream.KillSwitch
import akka.stream.scaladsl.Flow
import com.google.protobuf.ByteString
import io.cloudstate.eventing.{EventDestination => EventDestinationProto, EventSource => EventSourceProto}

import scala.concurrent.Future

/**
 * Eventing support.
 *
 * Different eventing implementations should implement this.
 */
trait EventingSupport {
  def name: String

  /** Can this eventing support implementation be used as a source? */
  def supportsSource: Boolean

  /** Create a source for the given name which should route to the given handler. */
  def createSource(source: EventSourceProto, serviceName: String): EventSource

  /** Can this eventing support implementation be used as a destination? */
  def supportsDestination: Boolean

  /** Create a destination for the given destination name using the given handler. */
  def createDestination(destination: EventDestinationProto): EventDestination
}

/** An event source. */
trait EventSource {

  /**
   * A type used to refer to the source event. The type of this and what it contains depends on the eventing
   * implementation, it could be the whole event, or it could just be an identifier. The purpose for this is to
   * allow the reference to be emitted from the flow to run the stream, so that it can be acknowledged.
   */
  type SourceEventRef

  /** Run this event source with the given flow. */
  def run(flow: Flow[SourceEvent[SourceEventRef], SourceEventRef, _]): Cancellable
}

/** An event destination. */
trait EventDestination {

  /**
   * The flow that consumes events to publish.
   *
   * This flow must produce one element for each event consumed, that message signals the acknowledgement that the
   * corresponding consumed element has been successfully published, hence the upstream source can now be acknowledged.
   * Order matters, the flow should now emit elements for events received later in the stream until the earlier events
   * in the stream have been successfully published with corresponding events emitted for acknowledgement.
   *
   * In general, implementations should not attempt to retry when publishing fails. Rather, if a failure in publishing
   * occurs, the stream should be terminated with an error. Cloudstate will then handle retries, using exponential
   * backoffs, or routing to dead letters, etc.
   */
  def eventStreamOut: Flow[DestinationEvent, AnyRef, NotUsed]

  /**
   * Emit a single destination event.
   *
   * This is used when emitting events from a service call that have not come from another event source, eg, they
   * have come from a gRPC call.
   */
  def emitSingle(destinationEvent: DestinationEvent): Future[Done]
}

/** An event produced by an event source. */
case class SourceEvent[Ref](
    event: CloudEvent,
    /** A reference to this source event */
    ref: Ref
)

/** An event to be published to a destination */
case class DestinationEvent(
    event: CloudEvent
)

case class CloudEvent(
    id: String,
    source: String,
    specversion: String,
    `type`: String,
    datacontenttype: String,
    dataschema: Option[String],
    subject: Option[String],
    time: Option[Instant],
    data: Option[ByteString]
)
