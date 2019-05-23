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
    Configuration("###warmup", 30.seconds, 100), "###warmup-entity", self, self
  ), "entity"))

  stateManager ! Command(
    entityId = "###warmup-entity",
    name = "foo",
    payload = Some(com.google.protobuf.any.Any("url", ByteString.EMPTY))
  )

  override def receive: Receive = {
    case ConcurrencyEnforcer.Action(_, _, start) => start()
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
      // There are a few other messages we'll receive that we don't care about
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}
