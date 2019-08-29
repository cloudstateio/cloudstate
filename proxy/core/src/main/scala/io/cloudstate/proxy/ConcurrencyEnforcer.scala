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

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Timers}
import ConcurrencyEnforcer.ConcurrencyEnforcerSettings

import scala.collection.immutable.Queue
import scala.concurrent.duration.{Deadline, FiniteDuration}

/**
  * This actor enforces the concurrency of requests going to the user code.
  *
  * Background reading necessary to understand before reading this:
  *
  * https://github.com/knative/serving/blob/master/docs/scaling/DEVELOPMENT.md
  *
  * The Autoscaler selects a desired concurrency level (though, this is currently not implemented and hardcoded at 1)
  * based on CPU/memory/other resource usage of the pod. This is referred to as slow brain scaling. We need to enforce
  * that concurrency level, and then also report metrics on outstanding requests to the user function. When the
  * autoscaler sees the number of queued request go up, it scales the deployment. This is referred to as fast brain
  * scaling.
  *
  * One challenge that we have is that not all our communication with the pod is request based - we also send init
  * messages and events, and these messages don't send a reply, so there's no way to measure how long they take to
  * process. We could potentially use backpressure to determine when they are processed, however it's more complex than
  * that because a lot of the time in hydrating the events will come from loading the database, which we don't want to
  * include in metrics.
  *
  * All this said, it's not as bad as it sounds. Currently we don't wait for events to be consumed by the user function
  * before sending the command. If the user function is slow at consuming them, then the command will end up queuing
  * behind the events, and the slow processing of events will cause the command to be delayed in being processed. So,
  * our strategy is to just report metrics on command handling, and let event handling just happen.
  */
object ConcurrencyEnforcer {
  final case class Action(id: String, start: () => Unit)
  final case class ActionCompleted(id: String, timeNanos: Long)

  final case class ConcurrencyEnforcerSettings(
    concurrency: Int,
    actionTimeout: FiniteDuration,
    cleanupPeriod: FiniteDuration
  )

  private case object Tick extends DeadLetterSuppression

  def props(settings: ConcurrencyEnforcerSettings, statsCollector: ActorRef): Props = Props(new ConcurrencyEnforcer(settings, statsCollector))

  private final case class OutstandingAction(deadline: Deadline)
}

class ConcurrencyEnforcer(settings: ConcurrencyEnforcerSettings, statsCollector: ActorRef) extends Actor with ActorLogging with Timers {
  import ConcurrencyEnforcer._

  private[this] final var outstanding = Map.empty[String, OutstandingAction]
  private[this] final var queue = Queue.empty[Action]

  timers.startPeriodicTimer("tick", Tick, settings.cleanupPeriod)

  override def receive: Receive = {
    // Concurrency of 0 means unlimited
    case a: Action if outstanding.size < settings.concurrency || settings.concurrency == 0 =>
      reportCommand(a)
      startAction(a)

    case a: Action =>
      reportCommand(a)
      queue = queue.enqueue(a)

    case ActionCompleted(id, timeNanos) =>
      if (outstanding.contains(id)) {
        completeAction(id, timeNanos)
      } else {
        if (queue.exists(_.id == id)) {
          // It's been completed before it's been executed, generally the state manager actor will already have
          // logged the reason for this, so just remove it from the queue.
          queue = queue.filterNot(_.id == id)
        } else {
          log.warning("Action {} was completed but wasn't outstanding", id)
        }
      }

    case Tick =>
      outstanding.foreach {
        case (id, action) if action.deadline.isOverdue() =>
          log.warning("Action {} has exceeded the action timeout of {}", id, settings.actionTimeout)
          completeAction(id, settings.actionTimeout.toNanos)
        case _ => // ok
      }
  }

  private def reportCommand(action: Action) = {
    statsCollector ! StatsCollector.CommandSent
  }

  private def reportReply(timeNanos: Long) = {
    statsCollector ! StatsCollector.ReplyReceived(timeNanos)
  }

  private def completeAction(id: String, timeNanos: Long) = {
    reportReply(timeNanos)

    outstanding -= id
    if (queue.nonEmpty) {
      val (action, newQueue) = queue.dequeue
      queue = newQueue
      startAction(action)
    }
  }

  private def startAction(action: Action)= {
    if (outstanding.contains(action.id)) {
      log.warning("Action {} already outstanding?", action.id)
    }
    outstanding += (action.id -> OutstandingAction(settings.actionTimeout.fromNow))
    action.start()
  }
}
