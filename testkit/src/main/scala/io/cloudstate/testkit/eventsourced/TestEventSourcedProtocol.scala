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

import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.testkit.TestProtocol.TestProtocolContext

final class TestEventSourcedProtocol(context: TestProtocolContext) {
  private val client = EventSourcedClient(context.clientSettings)(context.system)

  def connect(): TestEventSourcedProtocol.Connection = new TestEventSourcedProtocol.Connection(client, context)

  def terminate(): Unit = client.close()
}

object TestEventSourcedProtocol {
  final class Connection(client: EventSourcedClient, context: TestProtocolContext) {
    import context.system

    private val in = TestPublisher.probe[EventSourcedStreamIn]()
    private val out = client.handle(Source.fromPublisher(in)).runWith(TestSink.probe[EventSourcedStreamOut])

    out.ensureSubscription()

    def send(message: EventSourcedStreamIn.Message): Connection = {
      in.sendNext(EventSourcedStreamIn(message))
      this
    }

    def expect(message: EventSourcedStreamOut.Message): Connection = {
      out.request(1).expectNext(EventSourcedStreamOut(message))
      this
    }

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
