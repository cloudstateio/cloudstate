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
import io.cloudstate.testkit.http.TestHttpClient
import io.cloudstate.testkit.reflection.TestServerReflectionClient

final class TestClient(host: String, port: Int) {
  import TestClient._

  val context = new TestClientContext(host, port)

  val http = new TestHttpClient(context)

  val serverReflection = new TestServerReflectionClient(context)

  def settings: GrpcClientSettings = context.clientSettings

  def terminate(): Unit = {
    http.terminate()
    serverReflection.terminate()
    context.terminate()
  }
}

object TestClient {
  def apply(port: Int): TestClient = apply("localhost", port)
  def apply(host: String, port: Int): TestClient = new TestClient(host, port)

  final class TestClientContext(val host: String, val port: Int) {
    val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka.loglevel = ERROR
      akka.http.server {
        preview.enable-http2 = on
      }
    """))

    implicit val system: ActorSystem = ActorSystem("TestClient", config)

    val clientSettings: GrpcClientSettings = GrpcClientSettings.connectToServiceAt(host, port).withTls(false)

    def terminate(): Unit = TestKit.shutdownActorSystem(system)
  }
}
