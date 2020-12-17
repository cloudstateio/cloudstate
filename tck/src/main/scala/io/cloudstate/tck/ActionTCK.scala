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

import io.cloudstate.protocol.action.{ActionCommand, ActionProtocol, ActionResponse}
import io.cloudstate.tck.model.action.{ActionTckModel, ActionTwo}
import io.cloudstate.tck.model.action._
import io.cloudstate.testkit.action.ActionMessages._

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
      discoveredServices must (contain("ActionTckModel") and contain("ActionTwo"))
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
}
