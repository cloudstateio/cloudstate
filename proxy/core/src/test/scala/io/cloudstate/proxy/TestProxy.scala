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

import akka.actor.ActorSystem
import akka.testkit.TestEvent.Mute
import akka.testkit.{EventFilter, TestKit}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.entity.ProxyInfo
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.protocol.action.ActionProtocol
import io.cloudstate.testkit.Sockets
import java.net.{ConnectException, Socket}
import scala.concurrent.duration._

object TestProxy {
  def apply(servicePort: Int): TestProxy = new TestProxy(servicePort)
}

class TestProxy(servicePort: Int) {
  val port: Int = Sockets.temporaryLocalPort()

  val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
    include "dev-mode"
    akka {
      loglevel = ERROR
      loggers = ["akka.testkit.TestEventListener"]
      coordinated-shutdown.exit-jvm = off
      remote.artery.canonical.port = 0
      remote.artery.bind.port = ""
    }
    cloudstate.proxy {
      http-port = $port
      user-function-port = $servicePort
    }
  """))

  val info: ProxyInfo = EntityDiscoveryManager.proxyInfo(Seq(Crdt.name, ActionProtocol.name, EventSourced.name))

  val system: ActorSystem = CloudStateProxyMain.start(config)

  def isOnline: Boolean =
    try {
      new Socket("localhost", port).close()
      true
    } catch {
      case _: ConnectException => false
    }

  def expectOnline(): Unit = TestKit.awaitCond(isOnline, max = 10.seconds)

  def expectLogError[T](message: String)(block: => T): T =
    EventFilter.error(message, occurrences = 1).intercept(block)(system)

  def terminate(): Unit = {
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))
    TestKit.shutdownActorSystem(system)
  }
}
