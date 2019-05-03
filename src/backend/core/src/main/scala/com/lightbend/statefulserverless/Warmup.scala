package com.lightbend.statefulserverless

import akka.actor.{Actor, ActorLogging, SupervisorStrategy, Terminated}
import com.google.protobuf.ByteString
import com.lightbend.statefulserverless.StateManager.{Configuration, Stop}
import com.lightbend.statefulserverless.grpc.{Command, EntityStreamIn, EntityStreamOut, Reply}

import scala.concurrent.duration._

/**
  * Warms things up by starting a dummy instance of the state manager actor up, this ensures
  * Cassandra gets connected to etc, so a lot of classloading and jitting is done here.
  */
class Warmup extends Actor with ActorLogging {

  log.debug("Starting warmup...")

  private val stateManager = context.watch(context.actorOf(StateManager.props(
    Configuration("###warmup", 30.seconds, 100), "###warmup-entity", self
  ), "entity"))

  stateManager ! Command(
    entityId = "###warmup-entity",
    name = "foo",
    payload = Some(com.google.protobuf.any.Any("url", ByteString.EMPTY))
  )

  override def receive: Receive = {
    case EntityStreamIn(EntityStreamIn.Message.Event(_)) =>
      // Ignore
    case EntityStreamIn(EntityStreamIn.Message.Init(_)) =>
      // Ignore
    case EntityStreamIn(EntityStreamIn.Message.Command(cmd)) =>
      log.debug("Warmup got forwarded command")
      // It's forwarded us our command, send it a reply
      stateManager ! EntityStreamOut(EntityStreamOut.Message.Reply(Reply(
        commandId = cmd.id,
        payload = Some(com.google.protobuf.any.Any("url", ByteString.EMPTY))
      )))
    case _: ByteString =>
      log.debug("Warmup got forwarded reply")
      // It's forwarded the reply, now stop it
      stateManager ! Stop
    case Terminated(_) =>
      log.info("Warmup complete")
      context.stop(self)
    case _ =>
      // The state manager will send a success message to the relay to shut it down when it's told to stop
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}
