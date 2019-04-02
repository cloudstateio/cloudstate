package com.lightbend.statefulserverless

import com.lightbend.statefulserverless.grpc._

import akka.NotUsed
import akka.actor.ActorSystem
import scala.concurrent.Future
import akka.stream.scaladsl.Source

// TODO implement
final class EntityImpl(system: ActorSystem) extends Entity {
  override final def handle(in: Source[EntityStreamIn, NotUsed]): Source[EntityStreamOut, NotUsed] = Source.empty // FIXME IMPL
}
