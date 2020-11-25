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

package io.cloudstate.testkit.crdt

import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import io.cloudstate.protocol.crdt._
import io.cloudstate.testkit.TestProtocol.TestProtocolContext

final class TestCrdtProtocol(context: TestProtocolContext) {
  private val client = CrdtClient(context.clientSettings)(context.system)

  def connect(): TestCrdtProtocol.Connection = new TestCrdtProtocol.Connection(client, context)

  def terminate(): Unit = client.close()
}

object TestCrdtProtocol {
  final class Connection(client: CrdtClient, context: TestProtocolContext) {
    import context.system

    private val in = TestPublisher.probe[CrdtStreamIn]()
    private val out = client.handle(Source.fromPublisher(in)).runWith(TestSink.probe[CrdtStreamOut])

    out.ensureSubscription()

    def send(message: CrdtStreamIn.Message): Connection = {
      in.sendNext(CrdtStreamIn(message))
      this
    }

    def expect(message: CrdtStreamOut.Message): Connection = {
      out.request(1).expectNext(CrdtStreamOut(message))
      this
    }

    def expect(message: CrdtStreamOut.Message, transformReceived: CrdtStreamOut => CrdtStreamOut): Connection = {
      val received = transformReceived(out.request(1).expectNext())
      assert(CrdtStreamOut(message) == received, s"expected $message, found $received")
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
