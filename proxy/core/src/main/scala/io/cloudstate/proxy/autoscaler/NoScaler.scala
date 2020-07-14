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

package io.cloudstate.proxy.autoscaler

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.cluster.{Cluster, MemberStatus}
import Autoscaler.{Deployment, Scale}

/**
 * A scaler that doesn't do anything other than reports current cluster size.
 */
class NoScaler(autoscaler: ActorRef) extends Actor with ActorLogging {

  private[this] final val cluster = Cluster(context.system)

  cluster.subscribe(self, classOf[ClusterDomainEvent])
  sendDeployment()

  override def postStop(): Unit =
    cluster.unsubscribe(self)

  private def sendDeployment(): Unit =
    autoscaler ! Deployment(
      name = context.system.name,
      ready = cluster.state.members.count(c => c.status == MemberStatus.Up || c.status == MemberStatus.WeaklyUp),
      scale = cluster.state.members.size,
      upgrading = false
    )

  override def receive: Receive = {
    case Scale(_, scale) =>
      log.info(s"Autoscaler requested scale up to $scale")
    case _: ClusterDomainEvent =>
      // Don't care what the event was, just send the current deployment state to the autoscaler
      sendDeployment()
  }
}
