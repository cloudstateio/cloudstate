package com.lightbend.statefulserverless

import com.lightbend.statefulserverless.grpc._

import akka.NotUsed
import akka.actor.ActorSystem
import scala.concurrent.Future
import akka.stream.scaladsl.Source

// FIXME
// 1. call the ready-endpoint of the Entity service in the user container
// 2. introspect the returned descriptor to generate the request handler for the service(s)
// 3. on request
//    * verify inbound request against the service descriptor
//    * extract key and package the payload
//    * forward (ask) the payload to Cluster Sharding
// 4. establish call to handle-method of user container using Sink.actorRef
// 5. forward snapshot (Init) + Events at postStart to the ActorRef
// 6. forward Command to the ActorRef
// 7. assume that responses are 1-to-1 to Commands, track outstanding responses
// 8. as Responses come back, store any events enclosed, optionally store any snapshot enclosed, then respond with the payload

// TODO implement
final class EntityImpl(system: ActorSystem) extends Entity {
  override final def handle(in: Source[EntityStreamIn, NotUsed]): Source[EntityStreamOut, NotUsed] = Source.empty // FIXME IMPL
}
