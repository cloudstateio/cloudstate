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

package io.cloudstate.testkit

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.testkit.action.TestActionProtocol
import io.cloudstate.testkit.crdt.TestCrdtProtocol
import io.cloudstate.testkit.eventsourced.TestEventSourcedProtocol
import io.cloudstate.testkit.valueentity.TestValueEntityProtocol

final class TestProtocol(host: String, port: Int) {
  import TestProtocol._

  val context = new TestProtocolContext(host, port)

  val action = new TestActionProtocol(context)
  val crdt = new TestCrdtProtocol(context)
  val eventSourced = new TestEventSourcedProtocol(context)
  val valueEntity = new TestValueEntityProtocol(context)

  def settings: GrpcClientSettings = context.clientSettings

  def terminate(): Unit = {
    action.terminate()
    crdt.terminate()
    eventSourced.terminate()
    valueEntity.terminate()
    context.terminate()
  }
}

object TestProtocol {
  def apply(port: Int): TestProtocol = apply("localhost", port)
  def apply(host: String, port: Int): TestProtocol = new TestProtocol(host, port)

  final class TestProtocolContext(val host: String, val port: Int) {
    val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka.loglevel = ERROR
      akka.http.server {
        preview.enable-http2 = on
      }
    """))

    implicit val system: ActorSystem = ActorSystem("TestProtocol", config)

    val clientSettings: GrpcClientSettings = GrpcClientSettings.connectToServiceAt(host, port).withTls(false)

    def terminate(): Unit = TestKit.shutdownActorSystem(system)
  }
}
