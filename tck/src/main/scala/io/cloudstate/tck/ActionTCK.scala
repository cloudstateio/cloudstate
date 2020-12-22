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

package io.cloudstate.tck

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import io.cloudstate.protocol.action.{ActionCommand, ActionProtocol, ActionResponse}
import io.cloudstate.tck.model.action.{ActionTckModel, ActionTwo}
import io.cloudstate.tck.model.action._
import io.cloudstate.testkit.action.ActionMessages._
import io.grpc.StatusRuntimeException

trait ActionTCK extends TCKSpec {

  object ActionTCKModel {
    val Protocol: String = ActionProtocol.name
    val Service: String = ActionTckModel.name
    val ServiceTwo: String = ActionTwo.name

    def actionTest(test: => Any): Unit =
      testFor(ActionTckModel, ActionTwo)(test)

    def processUnary(request: Request): ActionCommand =
      command(Service, "ProcessUnary", request)

    val processStreamedIn: ActionCommand =
      command(Service, "ProcessStreamedIn")

    def processStreamedOut(request: Request): ActionCommand =
      command(Service, "ProcessStreamedOut", request)

    val processStreamed: ActionCommand =
      command(Service, "ProcessStreamed")

    def single(steps: ProcessStep*): Request =
      request(group(steps: _*))

    def request(groups: ProcessGroup*): Request =
      Request(groups)

    def group(steps: ProcessStep*): ProcessGroup =
      ProcessGroup(steps)

    def replyWith(message: String): ProcessStep =
      ProcessStep(ProcessStep.Step.Reply(Reply(message)))

    def serviceTwoCall(id: String): ActionCommand =
      command(ServiceTwo, "Call", OtherRequest(id))

    def sideEffectTo(id: String, synchronous: Boolean = false): ProcessStep =
      ProcessStep(ProcessStep.Step.Effect(SideEffect(id, synchronous)))

    def forwardTo(id: String): ProcessStep =
      ProcessStep(ProcessStep.Step.Forward(Forward(id)))

    def failWith(message: String): ProcessStep =
      ProcessStep(ProcessStep.Step.Fail(Fail(message)))

    def sideEffects(ids: String*): SideEffects =
      createSideEffects(synchronous = false, ids)

    def synchronousSideEffects(ids: String*): SideEffects =
      createSideEffects(synchronous = true, ids)

    def createSideEffects(synchronous: Boolean, ids: Seq[String]): SideEffects =
      ids.map(id => sideEffect(ServiceTwo, "Call", OtherRequest(id), synchronous))

    def forwarded(id: String, sideEffects: SideEffects = Seq.empty): ActionResponse =
      forward(ServiceTwo, "Call", OtherRequest(id), sideEffects)
  }

  def verifyActionModel(): Unit = {
    import ActionTCKModel._

    "verify action entity discovery" in actionTest {
      discoveredServices must (contain(Service) and contain(ServiceTwo))
      entity(ActionTCKModel.Service).value.entityType mustBe ActionTCKModel.Protocol
      entity(ActionTCKModel.ServiceTwo).value.entityType mustBe ActionTCKModel.Protocol
    }

    "verify unary command reply" in actionTest {
      protocol.action.unary
        .send(processUnary(single(replyWith("one"))))
        .expect(reply(Response("one")))
    }

    "verify unary command reply with side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(replyWith("two"), sideEffectTo("other"))))
        .expect(reply(Response("two"), sideEffects("other")))
    }

    "verify unary command reply with synchronous side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(replyWith("three"), sideEffectTo("another", synchronous = true))))
        .expect(reply(Response("three"), synchronousSideEffects("another")))
    }

    "verify unary command reply with multiple side effects" in actionTest {
      protocol.action.unary
        .send(processUnary(single(replyWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(reply(Response("four"), sideEffects("a", "b", "c")))
    }

    "verify unary command no reply" in actionTest {
      protocol.action.unary
        .send(processUnary(single()))
        .expect(noReply())
    }

    "verify unary command no reply with side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(sideEffectTo("other"))))
        .expect(noReply(sideEffects("other")))
    }

    "verify unary command no reply with synchronous side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(sideEffectTo("another", synchronous = true))))
        .expect(noReply(synchronousSideEffects("another")))
    }

    "verify unary command no reply with multiple side effects" in actionTest {
      protocol.action.unary
        .send(processUnary(single(sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(noReply(sideEffects("a", "b", "c")))
    }

    "verify unary command forward" in actionTest {
      protocol.action.unary
        .send(processUnary(single(forwardTo("one"))))
        .expect(forwarded("one"))
    }

    "verify unary command forward with side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(forwardTo("two"), sideEffectTo("other"))))
        .expect(forwarded("two", sideEffects("other")))
    }

    "verify unary command forward with synchronous side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(forwardTo("three"), sideEffectTo("another", synchronous = true))))
        .expect(forwarded("three", synchronousSideEffects("another")))
    }

    "verify unary command forward with multiple side effects" in actionTest {
      protocol.action.unary
        .send(processUnary(single(forwardTo("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(forwarded("four", sideEffects("a", "b", "c")))
    }

    "verify unary command failure" in actionTest {
      protocol.action.unary
        .send(processUnary(single(failWith("one"))))
        .expect(failure("one"))
    }

    "verify unary command failure with side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(failWith("two"), sideEffectTo("other"))))
        .expect(failure("two", sideEffects("other")))
    }

    "verify unary command failure with synchronous side effect" in actionTest {
      protocol.action.unary
        .send(processUnary(single(failWith("three"), sideEffectTo("another", synchronous = true))))
        .expect(failure("three", synchronousSideEffects("another")))
    }

    "verify unary command failure with multiple side effects" in actionTest {
      protocol.action.unary
        .send(processUnary(single(failWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(failure("four", sideEffects("a", "b", "c")))
    }

    "verify streamed-in command reply" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(replyWith("one"))))
        .complete()
        .expect(reply(Response("one")))
    }

    "verify streamed-in command reply with side effects" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(replyWith("two"), sideEffectTo("a"))))
        .send(command(single(sideEffectTo("b", synchronous = true))))
        .send(command(single(sideEffectTo("c"), sideEffectTo("d"))))
        .complete()
        .expect(reply(Response("two"), sideEffects("a") ++ synchronousSideEffects("b") ++ sideEffects("c", "d")))
    }

    "verify streamed-in command no reply (no requests)" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .complete()
        .expect(noReply())
    }

    "verify streamed-in command no reply" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single()))
        .complete()
        .expect(noReply())
    }

    "verify streamed-in command no reply with side effects" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(sideEffectTo("a"))))
        .send(command(single(sideEffectTo("b", synchronous = true))))
        .send(command(single(sideEffectTo("c"), sideEffectTo("d"))))
        .complete()
        .expect(noReply(sideEffects("a") ++ synchronousSideEffects("b") ++ sideEffects("c", "d")))
    }

    "verify streamed-in command forward" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(forwardTo("one"))))
        .complete()
        .expect(forwarded("one"))
    }

    "verify streamed-in command forward with side effects" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(forwardTo("two"), sideEffectTo("a"))))
        .send(command(single(sideEffectTo("b", synchronous = true))))
        .send(command(single(sideEffectTo("c"), sideEffectTo("d"))))
        .complete()
        .expect(forwarded("two", sideEffects("a") ++ synchronousSideEffects("b") ++ sideEffects("c", "d")))
    }

    "verify streamed-in command failure" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(failWith("one"))))
        .complete()
        .expect(failure("one"))
    }

    "verify streamed-in command failure with side effects" in actionTest {
      protocol.action
        .connectIn()
        .send(processStreamedIn)
        .send(command(single(failWith("two"), sideEffectTo("a"))))
        .send(command(single(sideEffectTo("b", synchronous = true))))
        .send(command(single(sideEffectTo("c"), sideEffectTo("d"))))
        .complete()
        .expect(failure("two", sideEffects("a") ++ synchronousSideEffects("b") ++ sideEffects("c", "d")))
    }

    "verify streamed-out command replies and side effects" in actionTest {
      protocol.action
        .connectOut()
        .send(
          processStreamedOut(
            request(
              group(replyWith("one")),
              group(replyWith("two"), sideEffectTo("other")),
              group(replyWith("three"), sideEffectTo("another", synchronous = true)),
              group(replyWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))
            )
          )
        )
        .expect(reply(Response("one")))
        .expect(reply(Response("two"), sideEffects("other")))
        .expect(reply(Response("three"), synchronousSideEffects("another")))
        .expect(reply(Response("four"), sideEffects("a", "b", "c")))
        .expectClosed()
    }

    "verify streamed-out command no replies and side effects" in actionTest {
      protocol.action
        .connectOut()
        .send(
          processStreamedOut(
            request(
              group(),
              group(sideEffectTo("other")),
              group(sideEffectTo("another", synchronous = true)),
              group(sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))
            )
          )
        )
        .expect(noReply())
        .expect(noReply(sideEffects("other")))
        .expect(noReply(synchronousSideEffects("another")))
        .expect(noReply(sideEffects("a", "b", "c")))
        .expectClosed()
    }

    "verify streamed-out command forwards and side effects" in actionTest {
      protocol.action
        .connectOut()
        .send(
          processStreamedOut(
            request(
              group(forwardTo("one")),
              group(forwardTo("two"), sideEffectTo("other")),
              group(forwardTo("three"), sideEffectTo("another", synchronous = true)),
              group(forwardTo("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))
            )
          )
        )
        .expect(forwarded("one"))
        .expect(forwarded("two", sideEffects("other")))
        .expect(forwarded("three", synchronousSideEffects("another")))
        .expect(forwarded("four", sideEffects("a", "b", "c")))
        .expectClosed()
    }

    "verify streamed-out command failures and side effects" in actionTest {
      protocol.action
        .connectOut()
        .send(
          processStreamedOut(
            request(
              group(failWith("one")),
              group(failWith("two"), sideEffectTo("other")),
              group(failWith("three"), sideEffectTo("another", synchronous = true)),
              group(failWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))
            )
          )
        )
        .expect(failure("one"))
        .expect(failure("two", sideEffects("other")))
        .expect(failure("three", synchronousSideEffects("another")))
        .expect(failure("four", sideEffects("a", "b", "c")))
        .expectClosed()
    }

    "verify streamed command replies and side effects" in actionTest {
      protocol.action
        .connect()
        .send(processStreamed)
        .send(command(single(replyWith("one"))))
        .expect(reply(Response("one")))
        .send(command(single(replyWith("two"), sideEffectTo("other"))))
        .expect(reply(Response("two"), sideEffects("other")))
        .send(command(single(replyWith("three"), sideEffectTo("another", synchronous = true))))
        .expect(reply(Response("three"), synchronousSideEffects("another")))
        .send(command(single(replyWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(reply(Response("four"), sideEffects("a", "b", "c")))
        .send(command(request(group(replyWith("five")), group(replyWith("six"), sideEffectTo("other")))))
        .expect(reply(Response("five")))
        .expect(reply(Response("six"), sideEffects("other")))
        .complete()
    }

    "verify streamed command no replies and side effects" in actionTest {
      protocol.action
        .connect()
        .send(processStreamed)
        .send(command(single()))
        .expect(noReply())
        .send(command(single(sideEffectTo("other"))))
        .expect(noReply(sideEffects("other")))
        .send(command(single(sideEffectTo("another", synchronous = true))))
        .expect(noReply(synchronousSideEffects("another")))
        .send(command(single(sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(noReply(sideEffects("a", "b", "c")))
        .send(command(request(group(), group(sideEffectTo("other")))))
        .expect(noReply())
        .expect(noReply(sideEffects("other")))
        .complete()
    }

    "verify streamed command forwards and side effects" in actionTest {
      protocol.action
        .connect()
        .send(processStreamed)
        .send(command(single(forwardTo("one"))))
        .expect(forwarded("one"))
        .send(command(single(forwardTo("two"), sideEffectTo("other"))))
        .expect(forwarded("two", sideEffects("other")))
        .send(command(single(forwardTo("three"), sideEffectTo("another", synchronous = true))))
        .expect(forwarded("three", synchronousSideEffects("another")))
        .send(command(single(forwardTo("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(forwarded("four", sideEffects("a", "b", "c")))
        .send(command(request(group(forwardTo("five")), group(forwardTo("six"), sideEffectTo("other")))))
        .expect(forwarded("five"))
        .expect(forwarded("six", sideEffects("other")))
        .complete()
    }

    "verify streamed command failures and side effects" in actionTest {
      protocol.action
        .connect()
        .send(processStreamed)
        .send(command(single(failWith("one"))))
        .expect(failure("one"))
        .send(command(single(failWith("two"), sideEffectTo("other"))))
        .expect(failure("two", sideEffects("other")))
        .send(command(single(failWith("three"), sideEffectTo("another", synchronous = true))))
        .expect(failure("three", synchronousSideEffects("another")))
        .send(command(single(failWith("four"), sideEffectTo("a"), sideEffectTo("b"), sideEffectTo("c"))))
        .expect(failure("four", sideEffects("a", "b", "c")))
        .send(command(request(group(failWith("five")), group(failWith("six"), sideEffectTo("other")))))
        .expect(failure("five"))
        .expect(failure("six", sideEffects("other")))
        .complete()
    }
  }

  object ActionTCKProxy {
    val tckModelClient: ActionTckModelClient = ActionTckModelClient(client.settings)(client.system)

    def terminate(): Unit = tckModelClient.close()
  }

  override def afterAll(): Unit =
    try ActionTCKProxy.terminate()
    finally super.afterAll()

  def verifyActionProxy(): Unit = {
    import ActionTCKModel._
    import ActionTCKProxy._

    "verify unary command processing" in actionTest {
      tckModelClient.processUnary(single(replyWith("one"))).futureValue mustBe Response("one")
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(replyWith("one"))))
        .expectOutgoing(reply(Response("one")))
    }

    "verify streamed-in command processing" in actionTest {
      implicit val actorSystem: ActorSystem = client.system
      val requests = TestPublisher.probe[Request]()
      val response = tckModelClient.processStreamedIn(Source.fromPublisher(requests))
      requests.sendNext(single(replyWith("two"))).sendComplete()
      response.futureValue mustBe Response("two")
      interceptor
        .expectActionStreamedInConnection()
        .expectIncoming(processStreamedIn)
        .expectIncoming(command(single(replyWith("two"))))
        .expectInComplete()
        .expectOutgoing(reply(Response("two")))
    }

    "verify streamed-out command processing" in actionTest {
      implicit val actorSystem: ActorSystem = client.system
      val streamedOutRequest = request(group(replyWith("A")), group(replyWith("B")), group(replyWith("C")))
      tckModelClient
        .processStreamedOut(streamedOutRequest)
        .runWith(TestSink.probe[Response])
        .ensureSubscription()
        .request(3)
        .expectNext(Response("A"))
        .expectNext(Response("B"))
        .expectNext(Response("C"))
        .expectComplete()
      interceptor
        .expectActionStreamedOutConnection()
        .expectIncoming(processStreamedOut(streamedOutRequest))
        .expectOutgoing(reply(Response("A")))
        .expectOutgoing(reply(Response("B")))
        .expectOutgoing(reply(Response("C")))
        .expectOutComplete()
    }

    "verify streamed command processing" in actionTest {
      implicit val actorSystem: ActorSystem = client.system
      val requests = TestPublisher.probe[Request]()
      val responses = tckModelClient
        .processStreamed(Source.fromPublisher(requests))
        .runWith(TestSink.probe[Response])
        .ensureSubscription()
      requests
        .sendNext(single(replyWith("X")))
        .sendNext(single(replyWith("Y")))
        .sendNext(single(replyWith("Z")))
        .sendComplete()
      responses
        .request(3)
        .expectNext(Response("X"))
        .expectNext(Response("Y"))
        .expectNext(Response("Z"))
        .expectComplete()
      interceptor
        .expectActionStreamedConnection()
        .expectIncoming(processStreamed)
        .expectIncoming(command(single(replyWith("X"))))
        .expectOutgoing(reply(Response("X")))
        .expectIncoming(command(single(replyWith("Y"))))
        .expectOutgoing(reply(Response("Y")))
        .expectIncoming(command(single(replyWith("Z"))))
        .expectOutgoing(reply(Response("Z")))
        .expectComplete()
    }

    "verify unary forwards and side effects" in actionTest {
      tckModelClient.processUnary(single(forwardTo("other"))).futureValue mustBe Response()
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(forwardTo("other"))))
        .expectOutgoing(forwarded("other"))
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(serviceTwoCall("other"))
        .expectOutgoing(reply(Response()))

      tckModelClient.processUnary(single(replyWith(""), sideEffectTo("another"))).futureValue mustBe Response()
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(replyWith(""), sideEffectTo("another"))))
        .expectOutgoing(reply(Response(), sideEffects("another")))
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(serviceTwoCall("another"))
        .expectOutgoing(reply(Response()))
    }

    "verify streamed forwards and side effects" in actionTest {
      implicit val actorSystem: ActorSystem = client.system
      val requests = TestPublisher.probe[Request]()
      val responses = tckModelClient
        .processStreamed(Source.fromPublisher(requests))
        .runWith(TestSink.probe[Response])
        .ensureSubscription()

      requests.sendNext(single(forwardTo("one")))
      responses.request(1).expectNext(Response())
      val connection = interceptor
        .expectActionStreamedConnection()
        .expectIncoming(processStreamed)
        .expectIncoming(command(single(forwardTo("one"))))
        .expectOutgoing(forwarded("one"))
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(serviceTwoCall("one"))
        .expectOutgoing(reply(Response()))

      requests.sendNext(single(sideEffectTo("two")))
      connection
        .expectIncoming(command(single(sideEffectTo("two"))))
        .expectOutgoing(noReply(sideEffects("two")))
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(serviceTwoCall("two"))
        .expectOutgoing(reply(Response()))

      requests.sendComplete()
      responses.expectComplete()
      connection.expectComplete()
    }

    "verify unary failures" in actionTest {
      val failed = tckModelClient.processUnary(single(failWith("expected failure"))).failed.futureValue
      failed mustBe a[StatusRuntimeException]
      failed.asInstanceOf[StatusRuntimeException].getStatus.getDescription mustBe "expected failure"
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(failWith("expected failure"))))
        .expectOutgoing(failure("expected failure"))
    }

    "verify streamed failures" in actionTest {
      implicit val actorSystem: ActorSystem = client.system
      val requests = TestPublisher.probe[Request]()
      val responses = tckModelClient
        .processStreamed(Source.fromPublisher(requests))
        .runWith(TestSink.probe[Response])
        .ensureSubscription()
      val connection = interceptor
        .expectActionStreamedConnection()
        .expectIncoming(processStreamed)
      requests.sendNext(single(failWith("expected failure")))
      val failed = responses.request(1).expectError()
      failed mustBe a[StatusRuntimeException]
      failed.asInstanceOf[StatusRuntimeException].getStatus.getDescription mustBe "expected failure"
      requests.expectCancellation()
      connection
        .expectIncoming(command(single(failWith("expected failure"))))
        .expectOutgoing(failure("expected failure"))
    }

    "verify unary HTTP API" in actionTest {
      client.http
        .request("tck/model/action/unary", """{"groups": [{"steps": [{"reply": {"message": "foo"}}]}]}""")
        .futureValue mustBe """{"message":"foo"}"""
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(replyWith("foo"))))
        .expectOutgoing(reply(Response("foo")))

      client.http
        .requestToError("tck/model/action/unary", """{"groups": [{"steps": [{"fail": {"message": "boom"}}]}]}""")
        .futureValue mustBe "boom"
      interceptor
        .expectActionUnaryConnection()
        .expectIncoming(processUnary(single(failWith("boom"))))
        .expectOutgoing(failure("boom"))
    }
  }
}
