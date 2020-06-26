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

package io.cloudstate.proxy.crdt

import java.net.URLEncoder

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, Terminated}
import io.cloudstate.proxy.entity.EntityCommand

import scala.collection.immutable.Queue

object CrdtEntityManager {
  final case object Passivate
  final case object Shutdown

  def props(entityProps: Props): Props = Props(new CrdtEntityManager(entityProps))
}

final class CrdtEntityManager(entityProps: Props) extends Actor with ActorLogging {

  import CrdtEntityManager._

  // Set of entities being passivated. If a new command arrives while it is being passivated, it will be buffered in
  // the queue, and the entity will be restarted once it finishes shutting down, and the queue drained to it.
  private[this] final var passivating = Map.empty[String, Queue[(EntityCommand, ActorRef)]]

  override def receive: Receive = {

    case command: EntityCommand =>
      val entityId = URLEncoder.encode(command.entityId, "utf-8")
      if (passivating.contains(entityId)) {
        passivating = passivating.updated(entityId, passivating(entityId).enqueue((command, sender())))
      } else {
        context.child(entityId) match {
          case Some(entity) =>
            entity forward command
          case None =>
            startEntity(entityId) forward command
        }
      }

    case Passivate =>
      if (sender().path.parent != self.path) {
        log.warning("Received request to passivate from non child actor: {}", sender())
      } else if (passivating.contains(sender().path.name)) {
        log.warning("Received request to passivate from already passivating actor: {}", sender())
        sender() ! CrdtEntity.Stop
      } else {
        passivating = passivating.updated(sender().path.name, Queue.empty)
        sender() ! CrdtEntity.Stop
      }

    case Terminated(actor) =>
      if (passivating.contains(actor.path.name)) {
        val queue = passivating(actor.path.name)
        passivating = passivating - actor.path.name

        if (queue.nonEmpty) {
          val entity = startEntity(actor.path.name)
          queue.foreach {
            case (command, initiator) =>
              entity.tell(command, initiator)
          }
        }
      }

    case Shutdown =>
      if (context.children.isEmpty) {
        sender() ! Done
        context.stop(self)
      } else {
        context.children
          .filterNot(c => passivating.contains(c.path.name))
          .foreach { child =>
            child ! CrdtEntity.Stop
          }
        context become stopping(sender())
      }
  }

  private def startEntity(entityId: String) =
    context.watch(context.actorOf(entityProps, entityId))

  private def stopping(reportStopped: ActorRef): Receive = {
    case Terminated(actor) =>
      // If we have queued messages, process them first
      if (passivating.contains(actor.path.name)) {
        val queue = passivating(actor.path.name)
        passivating = passivating - actor.path.name

        if (queue.nonEmpty) {
          // Restart so we can process just these messages
          val entity = startEntity(actor.path.name)
          queue.foreach {
            case (command, initiator) =>
              entity.tell(command, initiator)
          }
          // And then stop again
          entity ! CrdtEntity.Stop
        }
      }

      if (context.children.isEmpty) {
        reportStopped ! Done
        context.stop(self)
      }

    case Passivate =>
    // Ignore, we've already told all entities to stop

    case Shutdown =>
    // Ignore, we're already shutting down

    case command: EntityCommand =>
      log.warning("Received command {} for {} while shutting down, dropping.", command.name, command.entityId)
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}
