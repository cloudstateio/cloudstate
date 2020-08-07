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

package io.cloudstate.testkit.eventsourced

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.event_sourced._

final class TestEventSourcedClient(port: Int) {
  private val config: Config = ConfigFactory.load(ConfigFactory.parseString("""
    akka.http.server {
      preview.enable-http2 = on
    }
  """))

  private implicit val system: ActorSystem = ActorSystem("TestEventSourcedClient", config)
  private val client = EventSourcedClient(GrpcClientSettings.connectToServiceAt("localhost", port).withTls(false))

  def connect: TestEventSourcedClient.Connection = new TestEventSourcedClient.Connection(client, system)

  def terminate(): Unit = {
    client.close()
    TestKit.shutdownActorSystem(system)
  }
}

object TestEventSourcedClient {
  def apply(port: Int): TestEventSourcedClient = new TestEventSourcedClient(port)

  final class Connection(client: EventSourcedClient, system: ActorSystem) {
    private implicit val actorSystem: ActorSystem = system
    private val in = TestPublisher.probe[EventSourcedStreamIn]()
    private val out = client.handle(Source.fromPublisher(in)).runWith(TestSink.probe[EventSourcedStreamOut])

    out.ensureSubscription()

    def send(message: EventSourcedStreamIn.Message): Unit =
      in.sendNext(EventSourcedStreamIn(message))

    def expect(message: EventSourcedStreamOut.Message): Unit =
      out.request(1).expectNext(EventSourcedStreamOut(message))

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
