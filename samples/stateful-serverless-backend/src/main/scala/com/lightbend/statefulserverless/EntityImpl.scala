package com.lightbend.statefulserverless

import com.lightbend.statefulserverless.grpc._

import akka.NotUsed
import akka.actor.ActorSystem
import scala.concurrent.Future
import akka.stream.scaladsl.Source

// TODO implement
final class EntityImpl(system: ActorSystem) extends Entity {
  // The stream. One stream will be established per active entity.
  // Once established, the first message sent will be Init, which contains the entity ID, and,
  // if the entity has previously persisted a snapshot, it will contain that snapshot. It will
  // then send zero to many event messages, one for each event previously persisted. The entity
  // is expected to apply these to its state in a deterministic fashion. Once all the events
  // are sent, one to many commands are sent, with new commands being sent as new requests for
  // the entity come in. The entity is expected to reply to each command with exactly one reply
  // message. The entity should reply in order, and any events that the entity requests to be
  // persisted the entity should handle itself, applying them to its own state, as if they had
  // arrived as events when the event stream was being replayed on load.
  override final def handle(in: Source[EntityStreamIn, NotUsed]): Source[EntityStreamOut, NotUsed] = Source.empty // FIXME IMPL
}
