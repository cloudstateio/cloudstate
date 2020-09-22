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

import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import io.cloudstate.protocol.crud.{CrudClient, CrudStreamIn, CrudStreamOut}
import io.cloudstate.testkit.TestProtocol.TestProtocolContext

final class TestCrudProtocol(context: TestProtocolContext) {
  private val client = CrudClient(context.clientSettings)(context.system)

  def connect(): TestCrudProtocol.Connection = new TestCrudProtocol.Connection(client, context)

  def terminate(): Unit = client.close()
}

object TestCrudProtocol {

  final class Connection(client: CrudClient, context: TestProtocolContext) {
    import context.system

    private val in = TestPublisher.probe[CrudStreamIn]()
    private val out = client.handle(Source.fromPublisher(in)).runWith(TestSink.probe[CrudStreamOut])

    out.ensureSubscription()

    def send(message: CrudStreamIn.Message): Connection = {
      in.sendNext(CrudStreamIn(message))
      this
    }

    def expect(message: CrudStreamOut.Message): Connection = {
      out.request(1).expectNext(CrudStreamOut(message))
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
