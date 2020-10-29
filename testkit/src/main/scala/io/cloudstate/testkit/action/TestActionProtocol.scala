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

package io.cloudstate.testkit.action

import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import io.cloudstate.protocol.action._
import io.cloudstate.testkit.TestProtocol.TestProtocolContext
import scala.util.{Failure, Success}

final class TestActionProtocol(context: TestProtocolContext) {
  private val client = ActionProtocolClient(context.clientSettings)(context.system)

  val unary: TestActionProtocol.Unary =
    new TestActionProtocol.Unary(client, context)

  def connectIn(): TestActionProtocol.InConnection =
    new TestActionProtocol.InConnection(client, context)

  def connectOut(): TestActionProtocol.OutConnecting =
    new TestActionProtocol.OutConnecting(client, context)

  def connect(): TestActionProtocol.BidirectionalConnection =
    new TestActionProtocol.BidirectionalConnection(client, context)

  def terminate(): Unit = client.close()
}

object TestActionProtocol {
  final class Unary(client: ActionProtocolClient, context: TestProtocolContext) {
    private val out = TestProbe("UnaryOutProbe")(context.system)

    def send(command: ActionCommand): Unary = {
      client.handleUnary(command).onComplete(out.ref.!)(context.system.dispatcher)
      this
    }

    def expect(response: ActionResponse): Unit =
      out.expectMsg(Success(response))

    def expectError(throwable: Throwable): Unit =
      out.expectMsg(Failure(throwable))
  }

  final class InConnection(client: ActionProtocolClient, context: TestProtocolContext) {
    import context.system
    import context.system.dispatcher

    private val in = TestPublisher.probe[ActionCommand]()
    private val out = TestProbe("InConnectionOutProbe")(context.system)

    client.handleStreamedIn(Source.fromPublisher(in)).onComplete(out.ref.!)

    def send(command: ActionCommand): InConnection = {
      in.sendNext(command)
      this
    }

    def complete(): InConnection = {
      in.sendComplete()
      this
    }

    def expect(response: ActionResponse): Unit =
      out.expectMsg(Success(response))

    def expectError(throwable: Throwable): Unit =
      out.expectMsg(Failure(throwable))
  }

  final class OutConnecting(client: ActionProtocolClient, context: TestProtocolContext) {
    def send(command: ActionCommand): OutConnection = new OutConnection(client, context, command)
  }

  final class OutConnection(client: ActionProtocolClient, context: TestProtocolContext, command: ActionCommand) {
    import context.system

    private val out = client.handleStreamedOut(command).runWith(TestSink.probe[ActionResponse])

    out.ensureSubscription()

    def expect(response: ActionResponse): OutConnection = {
      out.request(1).expectNext(response)
      this
    }

    def expectClosed(): Unit =
      out.expectComplete()
  }

  final class BidirectionalConnection(client: ActionProtocolClient, context: TestProtocolContext) {
    import context.system

    private val in = TestPublisher.probe[ActionCommand]()
    private val out = client.handleStreamed(Source.fromPublisher(in)).runWith(TestSink.probe[ActionResponse])

    out.ensureSubscription()

    def send(command: ActionCommand): BidirectionalConnection = {
      in.sendNext(command)
      this
    }

    def expect(response: ActionResponse): BidirectionalConnection = {
      out.request(1).expectNext(response)
      this
    }

    def expectClosed(): Unit = {
      out.expectComplete()
      in.expectCancellation()
    }

    def complete(): Unit = {
      in.sendComplete()
      out.expectComplete()
    }
  }
}
