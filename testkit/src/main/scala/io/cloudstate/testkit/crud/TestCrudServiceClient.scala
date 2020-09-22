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

package io.cloudstate.testkit.crud

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.crud.{CrudClient, CrudStreamIn, CrudStreamOut}

class TestCrudServiceClient(port: Int) {
  private val config: Config = ConfigFactory.load(ConfigFactory.parseString("""
    akka.http.server {
      preview.enable-http2 = on
    }
  """))

  private implicit val system: ActorSystem = ActorSystem("TestCrudServiceClient", config)
  private val client = CrudClient(GrpcClientSettings.connectToServiceAt("localhost", port).withTls(false))

  def connect: TestCrudServiceClient.Connection = new TestCrudServiceClient.Connection(client, system)

  def terminate(): Unit = {
    client.close()
    TestKit.shutdownActorSystem(system)
  }
}

object TestCrudServiceClient {
  def apply(port: Int) = new TestCrudServiceClient(port)

  final class Connection(client: CrudClient, system: ActorSystem) {
    private implicit val actorSystem: ActorSystem = system
    private val in = TestPublisher.probe[CrudStreamIn]()
    private val out = client.handle(Source.fromPublisher(in)).runWith(TestSink.probe[CrudStreamOut])

    out.ensureSubscription()

    def send(message: CrudStreamIn.Message): Unit =
      in.sendNext(CrudStreamIn(message))

    def expect(message: CrudStreamOut.Message): Unit =
      out.request(1).expectNext(CrudStreamOut(message))

    def expectClosed(): Unit = {
      out.expectComplete()
      in.expectCancellation()
    }

    def passivate(): Unit = close()

    def close(): Unit = {
      in.sendComplete()
      out.expectComplete()
    }
  }
}
