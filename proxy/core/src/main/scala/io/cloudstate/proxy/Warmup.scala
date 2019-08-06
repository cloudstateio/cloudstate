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

package io.cloudstate.proxy

import akka.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated}
import com.google.protobuf.ByteString
import io.cloudstate.proxy.eventsourced.EventSourcedEntity.{Configuration, Stop}
import Warmup.Ready
import io.cloudstate.entity.{ClientAction, Reply}
import io.cloudstate.eventsourced.{EventSourcedReply, EventSourcedStreamIn, EventSourcedStreamOut}
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionCommand, UserFunctionReply}
import io.cloudstate.proxy.eventsourced.EventSourcedEntity

import scala.concurrent.duration._

object Warmup {
  def props(needsWarmup: Boolean): Props = Props(new Warmup(needsWarmup))

  case object Ready
}

/**
  * Warms things up by starting a dummy instance of the state manager actor up, this ensures
  * Cassandra gets connected to etc, so a lot of classloading and jitting is done here.
  */
class Warmup(needsWarmup: Boolean) extends Actor with ActorLogging {

  if (needsWarmup) {
    log.debug("Starting warmup...")

    val stateManager = context.watch(context.actorOf(EventSourcedEntity.props(
      Configuration("warmup.Service", "###warmup", 30.seconds, 100), "###warmup-entity", self, self, self
    ), "entity"))

    stateManager ! EntityCommand(
      entityId = "###warmup-entity",
      name = "foo",
      payload = Some(com.google.protobuf.any.Any("url", ByteString.EMPTY))
    )

    context become warmingUp(stateManager)
  }

  // Default will be overriden above if we need to warm up
  override def receive = warm

  private def warmingUp(eventSourcedEntityManager: ActorRef): Receive = {
    case Ready => sender ! false
    case ConcurrencyEnforcer.Action(_, start) =>
      log.debug("Warmup received action, starting it.")
      start()
    case EventSourcedStreamIn(EventSourcedStreamIn.Message.Event(_)) =>
      // Ignore
    case EventSourcedStreamIn(EventSourcedStreamIn.Message.Init(_)) =>
      log.debug("Warmup got init.")
      // Ignore
    case EventSourcedStreamIn(EventSourcedStreamIn.Message.Command(cmd)) =>
      log.debug("Warmup got forwarded command")
      // It's forwarded us our command, send it a reply
      eventSourcedEntityManager ! EventSourcedStreamOut(EventSourcedStreamOut.Message.Reply(EventSourcedReply(
        commandId = cmd.id,
        clientAction = Some(ClientAction(ClientAction.Action.Reply(Reply(Some(com.google.protobuf.any.Any("url", ByteString.EMPTY))))))
      )))
    case _: UserFunctionReply =>
      log.debug("Warmup got forwarded reply")
      // It's forwarded the reply, now stop it
      eventSourcedEntityManager ! Stop
    case Terminated(_) =>
      log.info("Warmup complete")
      context.become(warm)
    case other =>
      // There are a few other messages we'll receive that we don't care about
      log.debug("Warmup received {}", other.getClass)
  }

  private def warm: Receive = {
    case Ready => sender ! true
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}
