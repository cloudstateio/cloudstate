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
import akka.grpc.ServiceDescription
import akka.testkit.TestKit
import com.example.shoppingcart.shoppingcart.{
  ShoppingCart => EventSourcedShoppingCart,
  ShoppingCartClient => EventSourcedShoppingCartClient
}
import com.example.valueentity.shoppingcart.shoppingcart.{
  ShoppingCart => ValueEntityShoppingCart,
  ShoppingCartClient => ValueEntityShoppingCartClient
}
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.action._
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.value_entity.ValueEntity
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.tck.model.valueentity.valueentity.{ValueEntityTckModel, ValueEntityTwo}
import io.cloudstate.tck.model.action.{ActionTckModel, ActionTwo}
import io.cloudstate.tck.model.crdt.{CrdtTckModel, CrdtTwo}
import io.cloudstate.testkit.InterceptService.InterceptorSettings
import io.cloudstate.testkit.eventsourced.EventSourcedMessages
import io.cloudstate.testkit.{InterceptService, ServiceAddress, TestClient, TestProtocol}
import io.grpc.StatusRuntimeException
import io.cloudstate.tck.model.eventsourced.{EventSourcedTckModel, EventSourcedTwo}
import io.cloudstate.testkit.valueentity.ValueEntityMessages
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}

import scala.concurrent.duration._

object CloudStateTCK {
  final case class Settings(tck: ServiceAddress, proxy: ServiceAddress, service: ServiceAddress)

  object Settings {
    def fromConfig(config: Config): Settings = {
      val tckConfig = config.getConfig("cloudstate.tck")
      Settings(
        ServiceAddress(tckConfig.getString("hostname"), tckConfig.getInt("port")),
        ServiceAddress(tckConfig.getString("proxy.hostname"), tckConfig.getInt("proxy.port")),
        ServiceAddress(tckConfig.getString("service.hostname"), tckConfig.getInt("service.port"))
      )
    }
  }
}

class ConfiguredCloudStateTCK extends CloudStateTCK(CloudStateTCK.Settings.fromConfig(ConfigFactory.load()))

class CloudStateTCK(description: String, settings: CloudStateTCK.Settings)
    extends WordSpec
    with MustMatchers
    with BeforeAndAfterAll
    with ScalaFutures {

  def this(settings: CloudStateTCK.Settings) = this("", settings)

  private[this] final val system = ActorSystem("CloudStateTCK", ConfigFactory.load("tck"))

  private[this] final val client = TestClient(settings.proxy.host, settings.proxy.port)
  private[this] final val eventSourcedShoppingCartClient = EventSourcedShoppingCartClient(client.settings)(system)
  private[this] final val valueEntityShoppingCartClient = ValueEntityShoppingCartClient(client.settings)(system)

  private[this] final val protocol = TestProtocol(settings.service.host, settings.service.port)

  @volatile private[this] final var interceptor: InterceptService = _
  @volatile private[this] final var enabledServices = Seq.empty[String]

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 3.seconds, interval = 100.millis)

  override def beforeAll(): Unit =
    interceptor = new InterceptService(InterceptorSettings(bind = settings.tck, intercept = settings.service))

  override def afterAll(): Unit =
    try eventSourcedShoppingCartClient.close().futureValue
    finally try valueEntityShoppingCartClient.close().futureValue
    finally try client.terminate()
    finally try protocol.terminate()
    finally interceptor.terminate()

  def expectProxyOnline(): Unit =
    TestKit.awaitCond(client.http.probe(), max = 10.seconds)

  def testFor(services: ServiceDescription*)(test: => Any): Unit = {
    val enabled = services.map(_.name).forall(enabledServices.contains)
    if (enabled) test else pending
  }

  ("Cloudstate TCK " + description) when {

    "verifying discovery protocol" must {
      "verify proxy info and entity discovery" in {
        import scala.jdk.CollectionConverters._

        expectProxyOnline()

        val discovery = interceptor.expectEntityDiscovery()

        val info = discovery.expectProxyInfo()

        info.protocolMajorVersion mustBe 0
        info.protocolMinorVersion mustBe 2

        info.supportedEntityTypes must contain theSameElementsAs Seq(
          EventSourced.name,
          Crdt.name,
          ValueEntity.name,
          ActionProtocol.name
        )

        val spec = discovery.expectEntitySpec()

        val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
        val serviceNames = descriptorSet.getFileList.asScala.flatMap(_.getServiceList.asScala.map(_.getName))

        serviceNames.size mustBe spec.entities.size

        spec.entities.find(_.serviceName == ActionTckModel.name).foreach { entity =>
          serviceNames must contain("ActionTckModel")
          entity.entityType mustBe ActionProtocol.name
        }

        spec.entities.find(_.serviceName == ActionTwo.name).foreach { entity =>
          serviceNames must contain("ActionTwo")
          entity.entityType mustBe ActionProtocol.name
        }

        spec.entities.find(_.serviceName == CrdtTckModel.name).foreach { entity =>
          serviceNames must contain("CrdtTckModel")
          entity.entityType mustBe Crdt.name
        }

        spec.entities.find(_.serviceName == CrdtTwo.name).foreach { entity =>
          serviceNames must contain("CrdtTwo")
          entity.entityType mustBe Crdt.name
        }

        spec.entities.find(_.serviceName == EventSourcedTckModel.name).foreach { entity =>
          serviceNames must contain("EventSourcedTckModel")
          entity.entityType mustBe EventSourced.name
          entity.persistenceId mustBe "event-sourced-tck-model"
        }

        spec.entities.find(_.serviceName == EventSourcedTwo.name).foreach { entity =>
          serviceNames must contain("EventSourcedTwo")
          entity.entityType mustBe EventSourced.name
        }

        spec.entities.find(_.serviceName == EventSourcedShoppingCart.name).foreach { entity =>
          serviceNames must contain("ShoppingCart")
          entity.entityType mustBe EventSourced.name
          entity.persistenceId must not be empty
        }

        spec.entities.find(_.serviceName == ValueEntityTckModel.name).foreach { entity =>
          serviceNames must contain("ValueEntityTckModel")
          entity.entityType mustBe ValueEntity.name
          entity.persistenceId mustBe "value-entity-tck-model"
        }

        spec.entities.find(_.serviceName == ValueEntityTwo.name).foreach { entity =>
          serviceNames must contain("ValueEntityTwo")
          entity.entityType mustBe ValueEntity.name
          entity.persistenceId mustBe "value-entity-tck-model-two"
        }

        spec.entities.find(_.serviceName == ValueEntityShoppingCart.name).foreach { entity =>
          serviceNames must contain("ShoppingCart")
          entity.entityType mustBe ValueEntity.name
          entity.persistenceId must not be empty
        }

        enabledServices = spec.entities.map(_.serviceName)
      }
    }

    "verifying model test: actions" must {
      import io.cloudstate.tck.model.action._
      import io.cloudstate.testkit.action.ActionMessages._

      val Service = ActionTckModel.name
      val ServiceTwo = ActionTwo.name

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

    "verifying model test: CRDT entities" must {
      import io.cloudstate.tck.model.crdt._
      import io.cloudstate.testkit.crdt.CrdtMessages._

      val ServiceTwo = CrdtTwo.name

      var entityId: Int = 0

      def nextEntityId(crdtType: String): String = {
        entityId += 1; s"$crdtType-$entityId"
      }

      def crdtTest(crdtType: String)(test: String => Any): Unit =
        testFor(CrdtTckModel, CrdtTwo)(test(nextEntityId(crdtType)))

      def requestUpdate(update: Update): RequestAction =
        RequestAction(RequestAction.Action.Update(update))

      val requestDelete: RequestAction =
        RequestAction(RequestAction.Action.Delete(Delete()))

      val deleteCrdt: Effects =
        Effects(stateAction = crdtDelete)

      def forwardTo(id: String): RequestAction =
        RequestAction(RequestAction.Action.Forward(Forward(id)))

      def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
        RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

      def sideEffectsTo(ids: String*): Seq[RequestAction] =
        ids.map(id => sideEffectTo(id, synchronous = false))

      def sideEffects(ids: String*): Effects =
        createSideEffects(synchronous = false, ids)

      def synchronousSideEffects(ids: String*): Effects =
        createSideEffects(synchronous = true, ids)

      def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
        ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }

      def forwarded(id: Long, entityId: String, effects: Effects = Effects.empty) =
        forward(id, ServiceTwo, "Call", Request(entityId), effects)

      def failWith(message: String): RequestAction =
        RequestAction(RequestAction.Action.Fail(Fail(message)))

      // Sort sequences in received Deltas (using ScalaPB Lens support) for comparing easily
      object Delta {
        def sorted(out: CrdtStreamOut): CrdtStreamOut =
          out.update(_.reply.stateAction.update.modify(Delta.sort))

        def sort(delta: CrdtDelta): CrdtDelta =
          if (delta.delta.isGset)
            delta.update(
              _.gset.added.modify(_.sortBy(readPrimitiveString))
            )
          else if (delta.delta.isOrset)
            delta.update(
              _.orset.update(
                _.removed.modify(_.sortBy(readPrimitiveString)),
                _.added.modify(_.sortBy(readPrimitiveString))
              )
            )
          else if (delta.delta.isOrmap)
            delta.update(
              _.ormap.update(
                _.removed.modify(_.sortBy(readPrimitiveString)),
                _.updated.modify(_.map(_.update(_.delta.modify(Delta.sort))).sortBy(_.key.map(readPrimitiveString))),
                _.added.modify(_.map(_.update(_.delta.modify(Delta.sort))).sortBy(_.key.map(readPrimitiveString)))
              )
            )
          else delta
      }

      // GCounter tests

      object GCounter {
        def state(value: Long): Response =
          Response(Some(State(State.Value.Gcounter(GCounterValue(value)))))

        def incrementBy(increments: Long*): Seq[RequestAction] =
          increments.map(increment => requestUpdate(updateWith(increment)))

        def updateWith(increment: Long): Update =
          Update(Update.Update.Gcounter(GCounterUpdate(increment)))

        def update(value: Long): Effects =
          Effects(stateAction = crdtUpdate(delta(value)))

        def delta(value: Long): CrdtDelta.Delta.Gcounter = deltaGCounter(value)

        def test(run: String => Any): Unit = crdtTest("GCounter")(run)
      }

      "verify GCounter initial empty state" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, GCounter.state(0)))
          .passivate()
      }

      "verify GCounter state changes" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
          .expect(reply(1, GCounter.state(42), GCounter.update(42)))
          .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1, 2, 3))))
          .expect(reply(2, GCounter.state(48), GCounter.update(6)))
          .passivate()
      }

      "verify GCounter initial delta in init" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, GCounter.delta(42)))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(123))))
          .expect(reply(1, GCounter.state(165), GCounter.update(123)))
          .passivate()
      }

      "verify GCounter initial empty state with replicated initial delta" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(GCounter.delta(42)))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(123))))
          .expect(reply(1, GCounter.state(165), GCounter.update(123)))
          .passivate()
      }

      "verify GCounter mix of local and replicated state changes" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(1))))
          .expect(reply(1, GCounter.state(1), GCounter.update(1)))
          .send(delta(GCounter.delta(2)))
          .send(delta(GCounter.delta(3)))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, GCounter.state(6)))
          .send(command(3, id, "Process", Request(id, GCounter.incrementBy(4))))
          .expect(reply(3, GCounter.state(10), GCounter.update(4)))
          .send(delta(GCounter.delta(5)))
          .send(command(4, id, "Process", Request(id, GCounter.incrementBy(6))))
          .expect(reply(4, GCounter.state(21), GCounter.update(6)))
          .passivate()
      }

      "verify GCounter rehydration after passivation" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(1))))
          .expect(reply(1, GCounter.state(1), GCounter.update(1)))
          .send(delta(GCounter.delta(2)))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, GCounter.state(3)))
          .send(command(3, id, "Process", Request(id, GCounter.incrementBy(3))))
          .expect(reply(3, GCounter.state(6), GCounter.update(3)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, GCounter.delta(6)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, GCounter.state(6)))
          .send(delta(GCounter.delta(4)))
          .send(command(2, id, "Process", Request(id, GCounter.incrementBy(5))))
          .expect(reply(2, GCounter.state(15), GCounter.update(5)))
          .passivate()
      }

      "verify GCounter delete action received from entity" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
          .expect(reply(1, GCounter.state(42), GCounter.update(42)))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, GCounter.state(42), deleteCrdt))
          .passivate()
      }

      "verify GCounter delete action sent to entity" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // PNCounter tests

      object PNCounter {
        def state(value: Long): Response =
          Response(Some(State(State.Value.Pncounter(PNCounterValue(value)))))

        def changeBy(changes: Long*): Seq[RequestAction] =
          changes.map(change => requestUpdate(updateWith(change)))

        def updateWith(change: Long): Update =
          Update(Update.Update.Pncounter(PNCounterUpdate(change)))

        def update(value: Long): Effects =
          Effects(stateAction = crdtUpdate(delta(value)))

        def delta(value: Long): CrdtDelta.Delta.Pncounter = deltaPNCounter(value)

        def test(run: String => Any): Unit = crdtTest("PNCounter")(run)
      }

      "verify PNCounter initial empty state" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, PNCounter.state(0)))
          .passivate()
      }

      "verify PNCounter state changes" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1, -2, +3))))
          .expect(reply(1, PNCounter.state(+2), PNCounter.update(+2)))
          .send(command(2, id, "Process", Request(id, PNCounter.changeBy(-4, +5, -6))))
          .expect(reply(2, PNCounter.state(-3), PNCounter.update(-5)))
          .passivate()
      }

      "verify PNCounter initial delta in init" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, PNCounter.delta(42)))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(-123))))
          .expect(reply(1, PNCounter.state(-81), PNCounter.update(-123)))
          .passivate()
      }

      "verify PNCounter initial empty state with replicated initial delta" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(PNCounter.delta(-42)))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+123))))
          .expect(reply(1, PNCounter.state(81), PNCounter.update(+123)))
          .passivate()
      }

      "verify PNCounter mix of local and replicated state changes" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1))))
          .expect(reply(1, PNCounter.state(+1), PNCounter.update(+1)))
          .send(delta(PNCounter.delta(-2)))
          .send(delta(PNCounter.delta(+3)))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, PNCounter.state(+2)))
          .send(command(3, id, "Process", Request(id, PNCounter.changeBy(-4))))
          .expect(reply(3, PNCounter.state(-2), PNCounter.update(-4)))
          .send(delta(PNCounter.delta(+5)))
          .send(command(4, id, "Process", Request(id, PNCounter.changeBy(-6))))
          .expect(reply(4, PNCounter.state(-3), PNCounter.update(-6)))
          .passivate()
      }

      "verify PNCounter rehydration after passivation" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1))))
          .expect(reply(1, PNCounter.state(+1), PNCounter.update(+1)))
          .send(delta(PNCounter.delta(-2)))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, PNCounter.state(-1)))
          .send(command(3, id, "Process", Request(id, PNCounter.changeBy(+3))))
          .expect(reply(3, PNCounter.state(+2), PNCounter.update(+3)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, PNCounter.delta(+2)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, PNCounter.state(+2)))
          .send(delta(PNCounter.delta(-4)))
          .send(command(2, id, "Process", Request(id, PNCounter.changeBy(+5))))
          .expect(reply(2, PNCounter.state(+3), PNCounter.update(+5)))
          .passivate()
      }

      "verify PNCounter delete action received from entity" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+42))))
          .expect(reply(1, PNCounter.state(+42), PNCounter.update(+42)))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, PNCounter.state(+42), deleteCrdt))
          .passivate()
      }

      "verify PNCounter delete action sent to entity" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // GSet tests

      object GSet {
        def state(elements: String*): Response =
          Response(Some(State(State.Value.Gset(GSetValue(elements)))))

        def add(elements: String*): Seq[RequestAction] =
          elements.map(element => requestUpdate(updateWith(element)))

        def updateWith(element: String): Update =
          Update(Update.Update.Gset(GSetUpdate(element)))

        def update(added: String*): Effects =
          Effects(stateAction = crdtUpdate(delta(added: _*)))

        def delta(added: String*): CrdtDelta.Delta.Gset = deltaGSet(added.map(primitiveString))

        def test(run: String => Any): Unit = crdtTest("GSet")(run)
      }

      "verify GSet initial empty state" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, GSet.state()))
          .passivate()
      }

      "verify GSet state changes" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GSet.add("a", "b"))))
          .expect(reply(1, GSet.state("a", "b"), GSet.update("a", "b")), Delta.sorted)
          .send(command(2, id, "Process", Request(id, GSet.add("b", "c"))))
          .expect(reply(2, GSet.state("a", "b", "c"), GSet.update("c")))
          .send(command(3, id, "Process", Request(id, GSet.add("c", "a"))))
          .expect(reply(3, GSet.state("a", "b", "c")))
          .passivate()
      }

      "verify GSet initial delta in init" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, GSet.delta("a", "b", "c")))
          .send(command(1, id, "Process", Request(id, GSet.add("c", "d", "e"))))
          .expect(reply(1, GSet.state("a", "b", "c", "d", "e"), GSet.update("d", "e")), Delta.sorted)
          .passivate()
      }

      "verify GSet initial empty state with replicated initial delta" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(GSet.delta("x", "y")))
          .send(command(1, id, "Process", Request(id, GSet.add("z"))))
          .expect(reply(1, GSet.state("x", "y", "z"), GSet.update("z")))
          .passivate()
      }

      "verify GSet mix of local and replicated state changes" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GSet.add("a"))))
          .expect(reply(1, GSet.state("a"), GSet.update("a")))
          .send(delta(GSet.delta("a")))
          .send(delta(GSet.delta("b")))
          .send(delta(GSet.delta("c")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, GSet.state("a", "b", "c")))
          .send(command(3, id, "Process", Request(id, GSet.add("c", "d", "e"))))
          .expect(reply(3, GSet.state("a", "b", "c", "d", "e"), GSet.update("d", "e")), Delta.sorted)
          .send(delta(GSet.delta("f")))
          .send(command(4, id, "Process", Request(id, GSet.add("g", "d", "b"))))
          .expect(reply(4, GSet.state("a", "b", "c", "d", "e", "f", "g"), GSet.update("g")))
          .passivate()
      }

      "verify GSet rehydration after passivation" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GSet.add("a"))))
          .expect(reply(1, GSet.state("a"), GSet.update("a")))
          .send(delta(GSet.delta("b")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, GSet.state("a", "b")))
          .send(command(3, id, "Process", Request(id, GSet.add("c"))))
          .expect(reply(3, GSet.state("a", "b", "c"), GSet.update("c")))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, GSet.delta("a", "b", "c")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, GSet.state("a", "b", "c")))
          .send(delta(GSet.delta("d")))
          .send(command(2, id, "Process", Request(id, GSet.add("e"))))
          .expect(reply(2, GSet.state("a", "b", "c", "d", "e"), GSet.update("e")))
          .passivate()
      }

      "verify GSet delete action received from entity" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, GSet.add("x"))))
          .expect(reply(1, GSet.state("x"), GSet.update("x")))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, GSet.state("x"), deleteCrdt))
          .passivate()
      }

      "verify GSet delete action sent to entity" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // ORSet tests

      object ORSet {
        def state(elements: String*): Response =
          Response(Some(State(State.Value.Orset(ORSetValue(elements)))))

        def add(elements: String*): Seq[RequestAction] =
          elements.map(element => action(ORSetUpdate.Action.Add(element)))

        def remove(elements: String*): Seq[RequestAction] =
          elements.map(element => action(ORSetUpdate.Action.Remove(element)))

        def clear(value: Boolean = true): Seq[RequestAction] =
          Seq(action(ORSetUpdate.Action.Clear(value)))

        def action(updateAction: ORSetUpdate.Action): RequestAction =
          requestUpdate(Update(Update.Update.Orset(ORSetUpdate(updateAction))))

        def update(cleared: Boolean = false,
                   removed: Seq[String] = Seq.empty,
                   added: Seq[String] = Seq.empty): Effects =
          Effects(stateAction = crdtUpdate(delta(cleared, removed, added)))

        def delta(cleared: Boolean = false,
                  removed: Seq[String] = Seq.empty,
                  added: Seq[String] = Seq.empty): CrdtDelta.Delta.Orset =
          CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed.map(primitiveString), added.map(primitiveString)))

        def test(run: String => Any): Unit = crdtTest("ORSet")(run)
      }

      "verify ORSet initial empty state" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, ORSet.state()))
          .passivate()
      }

      "verify ORSet state changes" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORSet.add("a", "b"))))
          .expect(reply(1, ORSet.state("a", "b"), ORSet.update(added = Seq("a", "b"))), Delta.sorted)
          .send(command(2, id, "Process", Request(id, ORSet.add("b", "c"))))
          .expect(reply(2, ORSet.state("a", "b", "c"), ORSet.update(added = Seq("c"))))
          .send(command(3, id, "Process", Request(id, ORSet.add("c", "a"))))
          .expect(reply(3, ORSet.state("a", "b", "c")))
          .send(command(4, id, "Process", Request(id, ORSet.remove("b", "d"))))
          .expect(reply(4, ORSet.state("a", "c"), ORSet.update(removed = Seq("b"))))
          .send(command(5, id, "Process", Request(id, ORSet.remove("c", "d") ++ ORSet.add("b", "c"))))
          .expect(reply(5, ORSet.state("a", "b", "c"), ORSet.update(added = Seq("b"))))
          .send(command(6, id, "Process", Request(id, ORSet.clear())))
          .expect(reply(6, ORSet.state(), ORSet.update(cleared = true)))
          .send(command(7, id, "Process", Request(id, ORSet.add("a") ++ ORSet.clear() ++ ORSet.add("x"))))
          .expect(reply(7, ORSet.state("x"), ORSet.update(cleared = true, added = Seq("x"))))
          .passivate()
      }

      "verify ORSet initial delta in init" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, ORSet.delta(added = Seq("a", "b", "c"))))
          .send(command(1, id, "Process", Request(id, ORSet.remove("c") ++ ORSet.add("d"))))
          .expect(reply(1, ORSet.state("a", "b", "d"), ORSet.update(removed = Seq("c"), added = Seq("d"))))
          .passivate()
      }

      "verify ORSet initial empty state with replicated initial delta" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(ORSet.delta(added = Seq("x", "y"))))
          .send(command(1, id, "Process", Request(id, ORSet.remove("x") ++ ORSet.add("z"))))
          .expect(reply(1, ORSet.state("y", "z"), ORSet.update(removed = Seq("x"), added = Seq("z"))))
          .passivate()
      }

      "verify ORSet mix of local and replicated state changes" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORSet.add("a"))))
          .expect(reply(1, ORSet.state("a"), ORSet.update(added = Seq("a"))))
          .send(delta(ORSet.delta(added = Seq("a", "b"))))
          .send(delta(ORSet.delta(added = Seq("c", "d"))))
          .send(delta(ORSet.delta(removed = Seq("b", "d"))))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, ORSet.state("a", "c")))
          .send(command(3, id, "Process", Request(id, ORSet.add("c", "d", "e"))))
          .expect(reply(3, ORSet.state("a", "c", "d", "e"), ORSet.update(added = Seq("d", "e"))), Delta.sorted)
          .send(delta(ORSet.delta(removed = Seq("a", "c"), added = Seq("f"))))
          .send(command(4, id, "Process", Request(id, ORSet.add("g") ++ ORSet.remove("a", "d"))))
          .expect(reply(4, ORSet.state("e", "f", "g"), ORSet.update(removed = Seq("d"), added = Seq("g"))))
          .send(delta(ORSet.delta(cleared = true, added = Seq("x", "y", "z"))))
          .send(command(5, id, "Process", Request(id, ORSet.add("q", "x") ++ ORSet.remove("z"))))
          .expect(reply(5, ORSet.state("q", "x", "y"), ORSet.update(removed = Seq("z"), added = Seq("q"))))
          .send(delta(ORSet.delta(removed = Seq("x", "y"), added = Seq("r", "p"))))
          .send(command(6, id, "Process", Request(id, ORSet.add("s"))))
          .expect(reply(6, ORSet.state("p", "q", "r", "s"), ORSet.update(added = Seq("s"))))
          .send(delta(ORSet.delta(added = Seq("a", "b", "c"))))
          .send(command(7, id, "Process", Request(id, ORSet.clear() ++ ORSet.add("abc"))))
          .expect(reply(7, ORSet.state("abc"), ORSet.update(cleared = true, added = Seq("abc"))))
          .passivate()
      }

      "verify ORSet rehydration after passivation" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORSet.add("a"))))
          .expect(reply(1, ORSet.state("a"), ORSet.update(added = Seq("a"))))
          .send(delta(ORSet.delta(removed = Seq("a"), added = Seq("b"))))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, ORSet.state("b")))
          .send(command(3, id, "Process", Request(id, ORSet.add("c"))))
          .expect(reply(3, ORSet.state("b", "c"), ORSet.update(added = Seq("c"))))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, ORSet.delta(added = Seq("b", "c"))))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, ORSet.state("b", "c")))
          .send(delta(ORSet.delta(cleared = true, added = Seq("p", "q"))))
          .send(command(2, id, "Process", Request(id, ORSet.add("x", "y") ++ ORSet.remove("x"))))
          .expect(reply(2, ORSet.state("p", "q", "y"), ORSet.update(added = Seq("y"))))
          .passivate()
      }

      "verify ORSet delete action received from entity" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORSet.add("x"))))
          .expect(reply(1, ORSet.state("x"), ORSet.update(added = Seq("x"))))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, ORSet.state("x"), deleteCrdt))
          .passivate()
      }

      "verify ORSet delete action sent to entity" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // LWWRegister tests

      object LWWRegister {
        val DefaultClock: LWWRegisterClockType = LWWRegisterClockType.DEFAULT
        val ReverseClock: LWWRegisterClockType = LWWRegisterClockType.REVERSE
        val CustomClock: LWWRegisterClockType = LWWRegisterClockType.CUSTOM
        val CustomAutoIncClock: LWWRegisterClockType = LWWRegisterClockType.CUSTOM_AUTO_INCREMENT

        def state(value: String): Response =
          Response(Some(State(State.Value.Lwwregister(LWWRegisterValue(value)))))

        def setTo(value: String,
                  clockType: LWWRegisterClockType = DefaultClock,
                  customClockValue: Long = 0L): Seq[RequestAction] =
          updateWith(LWWRegisterUpdate(value, Some(LWWRegisterClock(clockType, customClockValue))))

        def updateWith(update: LWWRegisterUpdate): Seq[RequestAction] =
          Seq(requestUpdate(Update(Update.Update.Lwwregister(update))))

        def update(value: String, clock: CrdtClock = CrdtClock.DEFAULT, customClockValue: Long = 0L): Effects =
          Effects(stateAction = crdtUpdate(delta(value, clock, customClockValue)))

        def delta(value: String,
                  clock: CrdtClock = CrdtClock.DEFAULT,
                  customClockValue: Long = 0L): CrdtDelta.Delta.Lwwregister =
          deltaLWWRegister(Some(primitiveString(value)), clock, customClockValue)

        def test(run: String => Any): Unit = crdtTest("LWWRegister")(run)
      }

      "verify LWWRegister initial empty state" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, LWWRegister.state(""), LWWRegister.update("")))
          .passivate()
      }

      "verify LWWRegister state changes" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, LWWRegister.setTo("one"))))
          .expect(reply(1, LWWRegister.state("one"), LWWRegister.update("one")))
          .send(command(2, id, "Process", Request(id, LWWRegister.setTo("two"))))
          .expect(reply(2, LWWRegister.state("two"), LWWRegister.update("two")))
          .passivate()
      }

      "verify LWWRegister initial delta in init" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, LWWRegister.delta("one")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, LWWRegister.state("one")))
          .passivate()
      }

      "verify LWWRegister initial empty state with replicated initial delta" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(LWWRegister.delta("one")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, LWWRegister.state("one")))
          .passivate()
      }

      "verify LWWRegister mix of local and replicated state changes" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A"))))
          .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A")))
          .send(delta(LWWRegister.delta("B")))
          .send(delta(LWWRegister.delta("C")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, LWWRegister.state("C")))
          .send(command(3, id, "Process", Request(id, LWWRegister.setTo("D"))))
          .expect(reply(3, LWWRegister.state("D"), LWWRegister.update("D")))
          .send(delta(LWWRegister.delta("D")))
          .send(command(4, id, "Process", Request(id, LWWRegister.setTo("E"))))
          .expect(reply(4, LWWRegister.state("E"), LWWRegister.update("E")))
          .passivate()
      }

      "verify LWWRegister state changes with clock settings" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.ReverseClock))))
          .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.REVERSE)))
          .send(command(2, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.CustomClock, 123L))))
          .expect(reply(2, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.CUSTOM, 123L)))
          .send(command(2, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.CustomAutoIncClock, 456L))))
          .expect(reply(2, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.CUSTOM_AUTO_INCREMENT, 456L)))
          .passivate()
      }

      "verify LWWRegister rehydration after passivation" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A"))))
          .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A")))
          .send(delta(LWWRegister.delta("B")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, LWWRegister.state("B")))
          .send(command(3, id, "Process", Request(id, LWWRegister.setTo("C"))))
          .expect(reply(3, LWWRegister.state("C"), LWWRegister.update("C")))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, LWWRegister.delta("C")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, LWWRegister.state("C")))
          .send(delta(LWWRegister.delta("D")))
          .send(command(2, id, "Process", Request(id, LWWRegister.setTo("E"))))
          .expect(reply(2, LWWRegister.state("E"), LWWRegister.update("E")))
          .passivate()
      }

      "verify LWWRegister delete action received from entity" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, LWWRegister.setTo("one"))))
          .expect(reply(1, LWWRegister.state("one"), LWWRegister.update("one")))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, LWWRegister.state("one"), deleteCrdt))
          .passivate()
      }

      "verify LWWRegister delete action sent to entity" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // Flag tests

      object Flag {
        def state(value: Boolean): Response =
          Response(Some(State(State.Value.Flag(FlagValue(value)))))

        def enable(): Seq[RequestAction] =
          Seq(requestUpdate(Update(Update.Update.Flag(FlagUpdate()))))

        def update(value: Boolean): Effects =
          Effects(stateAction = crdtUpdate(delta(value)))

        def delta(value: Boolean): CrdtDelta.Delta.Flag = deltaFlag(value)

        def test(run: String => Any): Unit = crdtTest("Flag")(run)
      }

      "verify Flag initial empty state" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(false)))
          .passivate()
      }

      "verify Flag state changes" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Flag.enable())))
          .expect(reply(1, Flag.state(true), Flag.update(true)))
          .passivate()
      }

      "verify Flag initial delta in init" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, Flag.delta(false)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(false)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, Flag.delta(true)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(true)))
          .passivate()
      }

      "verify Flag initial empty state with replicated initial delta" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Flag.delta(false)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(false)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Flag.delta(true)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(true)))
          .passivate()
      }

      "verify Flag mix of local and replicated state changes" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Flag.delta(false)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(false)))
          .send(command(2, id, "Process", Request(id, Flag.enable())))
          .expect(reply(2, Flag.state(true), Flag.update(true)))
          .send(delta(Flag.delta(true)))
          .send(delta(Flag.delta(false)))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Flag.state(true)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Flag.delta(true)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(true)))
          .send(command(2, id, "Process", Request(id, Flag.enable())))
          .expect(reply(2, Flag.state(true)))
          .send(delta(Flag.delta(true)))
          .send(delta(Flag.delta(false)))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Flag.state(true)))
          .passivate()
      }

      "verify Flag rehydration after passivation" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(false)))
          .send(command(2, id, "Process", Request(id, Flag.enable())))
          .expect(reply(2, Flag.state(true), Flag.update(true)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, Flag.delta(true)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Flag.state(true)))
          .passivate()
      }

      "verify Flag delete action received from entity" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Flag.enable())))
          .expect(reply(1, Flag.state(true), Flag.update(true)))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, Flag.state(true), deleteCrdt))
          .passivate()
      }

      "verify Flag delete action sent to entity" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // ORMap tests

      object ORMap {
        def state(entries: (String, Response)*): Response =
          Response(
            Some(State(State.Value.Ormap(ORMapValue(entries.map {
              case (key, value) => ORMapEntryValue(key, value.state)
            }))))
          )

        def add(keys: String*): Seq[RequestAction] =
          keys.map(key => action(ORMapUpdate.Action.Add(key)))

        def updateWith(entries: (String, Seq[RequestAction])*): Seq[RequestAction] =
          entries flatMap {
            case (key, actions) =>
              actions map { a =>
                action(ORMapUpdate.Action.Update(ORMapEntryUpdate(key, Option(a.getUpdate))))
              }
          }

        def remove(keys: String*): Seq[RequestAction] =
          keys.map(key => action(ORMapUpdate.Action.Remove(key)))

        def clear(value: Boolean = true): Seq[RequestAction] =
          Seq(action(ORMapUpdate.Action.Clear(value)))

        def action(updateAction: ORMapUpdate.Action): RequestAction =
          requestUpdate(Update(Update.Update.Ormap(ORMapUpdate(updateAction))))

        def update(cleared: Boolean = false,
                   removed: Seq[String] = Seq.empty,
                   updated: Seq[(String, Effects)] = Seq.empty,
                   added: Seq[(String, Effects)] = Seq.empty): Effects =
          Effects(
            stateAction = crdtUpdate(
              delta(
                cleared,
                removed,
                updated map { case (key, effects) => key -> effects.stateAction.get.getUpdate.delta },
                added map { case (key, effects) => key -> effects.stateAction.get.getUpdate.delta }
              )
            )
          )

        def delta(cleared: Boolean = false,
                  removed: Seq[String] = Seq.empty,
                  updated: Seq[(String, CrdtDelta.Delta)] = Seq.empty,
                  added: Seq[(String, CrdtDelta.Delta)] = Seq.empty): CrdtDelta.Delta.Ormap =
          CrdtDelta.Delta.Ormap(
            ORMapDelta(
              cleared,
              removed.map(primitiveString),
              updated map { case (key, delta) => ORMapEntryDelta(Some(primitiveString(key)), Some(CrdtDelta(delta))) },
              added map { case (key, delta) => ORMapEntryDelta(Some(primitiveString(key)), Some(CrdtDelta(delta))) }
            )
          )

        def test(run: String => Any): Unit = crdtTest("ORMap")(run)
      }

      "verify ORMap initial empty state" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, ORMap.state()))
          .passivate()
      }

      "verify ORMap state changes" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORMap.add("PNCounter-1", "ORSet-1"))))
          .expect(
            reply(
              1,
              ORMap.state(
                "ORSet-1" -> ORSet.state(),
                "PNCounter-1" -> PNCounter.state(0)
              ),
              ORMap.update(
                added = Seq(
                  "ORSet-1" -> ORSet.update(),
                  "PNCounter-1" -> PNCounter.update(0)
                )
              )
            ),
            Delta.sorted
          )
          .send(
            command(2,
                    id,
                    "Process",
                    Request(id,
                            ORMap.add("ORSet-1") ++ ORMap.updateWith(
                              "LWWRegister-1" -> LWWRegister.setTo("zero")
                            )))
          )
          .expect(
            reply(
              2,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("zero"),
                "ORSet-1" -> ORSet.state(),
                "PNCounter-1" -> PNCounter.state(0)
              ),
              ORMap.update(
                added = Seq(
                  "LWWRegister-1" -> LWWRegister.update("zero")
                )
              )
            ),
            Delta.sorted
          )
          .send(
            command(3,
                    id,
                    "Process",
                    Request(id,
                            ORMap.updateWith(
                              "PNCounter-1" -> PNCounter.changeBy(+42),
                              "LWWRegister-1" -> LWWRegister.setTo("one")
                            )))
          )
          .expect(
            reply(
              3,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("one"),
                "ORSet-1" -> ORSet.state(),
                "PNCounter-1" -> PNCounter.state(+42)
              ),
              ORMap.update(
                updated = Seq(
                  "LWWRegister-1" -> LWWRegister.update("one"),
                  "PNCounter-1" -> PNCounter.update(+42)
                )
              )
            ),
            Delta.sorted
          )
          .send(command(4, id, "Process", Request(id, ORMap.updateWith("ORSet-1" -> ORSet.add("a", "b", "c")))))
          .expect(
            reply(
              4,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("one"),
                "ORSet-1" -> ORSet.state("a", "b", "c"),
                "PNCounter-1" -> PNCounter.state(+42)
              ),
              ORMap.update(
                updated = Seq(
                  "ORSet-1" -> ORSet.update(added = Seq("a", "b", "c"))
                )
              )
            ),
            Delta.sorted
          )
          .send(
            command(
              5,
              id,
              "Process",
              Request(id,
                      ORMap.updateWith(
                        "ORSet-1" -> (ORSet.remove("b") ++ ORSet.add("d")),
                        "PNCounter-1" -> PNCounter.changeBy(-123),
                        "LWWRegister-1" -> LWWRegister.setTo("two")
                      ))
            )
          )
          .expect(
            reply(
              5,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("two"),
                "ORSet-1" -> ORSet.state("a", "c", "d"),
                "PNCounter-1" -> PNCounter.state(-81)
              ),
              ORMap.update(
                updated = Seq(
                  "LWWRegister-1" -> LWWRegister.update("two"),
                  "ORSet-1" -> ORSet.update(removed = Seq("b"), added = Seq("d")),
                  "PNCounter-1" -> PNCounter.update(-123)
                )
              )
            ),
            Delta.sorted
          )
          .send(command(6, id, "Process", Request(id, ORMap.remove("ORSet-1"))))
          .expect(
            reply(
              6,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("two"),
                "PNCounter-1" -> PNCounter.state(-81)
              ),
              ORMap.update(removed = Seq("ORSet-1"))
            ),
            Delta.sorted
          )
          .send(
            command(7,
                    id,
                    "Process",
                    Request(id, ORMap.remove("LWWRegister-1") ++ ORMap.updateWith("Flag-1" -> Flag.enable())))
          )
          .expect(
            reply(
              7,
              ORMap.state(
                "Flag-1" -> Flag.state(true),
                "PNCounter-1" -> PNCounter.state(-81)
              ),
              ORMap.update(removed = Seq("LWWRegister-1"), added = Seq("Flag-1" -> Flag.update(true)))
            ),
            Delta.sorted
          )
          .send(command(8, id, "Process", Request(id, ORMap.clear())))
          .expect(reply(8, ORMap.state(), ORMap.update(cleared = true)))
          .send(
            command(
              9,
              id,
              "Process",
              Request(id,
                      ORMap.add("PNCounter-2") ++ ORMap.clear() ++ ORMap.updateWith(
                        "GCounter-1" -> GCounter.incrementBy(123)
                      ))
            )
          )
          .expect(
            reply(9,
                  ORMap.state("GCounter-1" -> GCounter.state(123)),
                  ORMap.update(cleared = true, added = Seq("GCounter-1" -> GCounter.update(123)))),
            Delta.sorted
          )
          .passivate()
      }

      "verify ORMap initial delta in init" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(
            init(
              CrdtTckModel.name,
              id,
              ORMap.delta(
                added = Seq("PNCounter-1" -> PNCounter.delta(+123),
                            "LWWRegister-1" -> LWWRegister.delta("one"),
                            "GSet-1" -> GSet.delta("a", "b", "c"))
              )
            )
          )
          .send(command(1, id, "Process", Request(id)))
          .expect(
            reply(1,
                  ORMap.state("GSet-1" -> GSet.state("a", "b", "c"),
                              "LWWRegister-1" -> LWWRegister.state("one"),
                              "PNCounter-1" -> PNCounter.state(+123)))
          )
          .send(
            command(
              2,
              id,
              "Process",
              Request(id, ORMap.remove("LWWRegister-1") ++ ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-42)))
            )
          )
          .expect(
            reply(
              2,
              ORMap.state("GSet-1" -> GSet.state("a", "b", "c"), "PNCounter-1" -> PNCounter.state(+81)),
              ORMap.update(removed = Seq("LWWRegister-1"), updated = Seq("PNCounter-1" -> PNCounter.update(-42)))
            ),
            Delta.sorted
          )
          .passivate()
      }

      "verify ORMap initial empty state with replicated initial delta" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(
            delta(
              ORMap.delta(
                added = Seq("GCounter-1" -> GCounter.delta(42),
                            "ORSet-1" -> ORSet.delta(added = Seq("a", "b", "c")),
                            "Flag-1" -> Flag.delta(false))
              )
            )
          )
          .send(command(1, id, "Process", Request(id)))
          .expect(
            reply(1,
                  ORMap.state("Flag-1" -> Flag.state(false),
                              "GCounter-1" -> GCounter.state(42),
                              "ORSet-1" -> ORSet.state("a", "b", "c")))
          )
          .passivate()
      }

      "verify ORMap mix of local and replicated state changes" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORMap.add("PNCounter-1"))))
          .expect(
            reply(
              1,
              ORMap.state(
                "PNCounter-1" -> PNCounter.state(0)
              ),
              ORMap.update(
                added = Seq(
                  "PNCounter-1" -> PNCounter.update(0)
                )
              )
            ),
            Delta.sorted
          )
          .send(delta(ORMap.delta(added = Seq("ORSet-1" -> ORSet.delta(added = Seq("x"))))))
          .send(
            delta(
              ORMap.delta(added = Seq("LWWRegister-1" -> LWWRegister.delta("one")),
                          updated = Seq("ORSet-1" -> ORSet.delta(added = Seq("y", "z"))))
            )
          )
          .send(
            command(2,
                    id,
                    "Process",
                    Request(id,
                            ORMap.add("ORSet-2") ++ ORMap.updateWith(
                              "LWWRegister-2" -> LWWRegister.setTo("two")
                            )))
          )
          .expect(
            reply(
              2,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("one"),
                "LWWRegister-2" -> LWWRegister.state("two"),
                "ORSet-1" -> ORSet.state("x", "y", "z"),
                "ORSet-2" -> ORSet.state(),
                "PNCounter-1" -> PNCounter.state(0)
              ),
              ORMap.update(
                added = Seq(
                  "LWWRegister-2" -> LWWRegister.update("two"),
                  "ORSet-2" -> ORSet.update()
                )
              )
            ),
            Delta.sorted
          )
          .send(
            command(3,
                    id,
                    "Process",
                    Request(id,
                            ORMap.updateWith(
                              "PNCounter-1" -> PNCounter.changeBy(+42),
                              "LWWRegister-1" -> LWWRegister.setTo("ONE")
                            )))
          )
          .expect(
            reply(
              3,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("ONE"),
                "LWWRegister-2" -> LWWRegister.state("two"),
                "ORSet-1" -> ORSet.state("x", "y", "z"),
                "ORSet-2" -> ORSet.state(),
                "PNCounter-1" -> PNCounter.state(+42)
              ),
              ORMap.update(
                updated = Seq(
                  "LWWRegister-1" -> LWWRegister.update("ONE"),
                  "PNCounter-1" -> PNCounter.update(+42)
                )
              )
            ),
            Delta.sorted
          )
          .send(
            delta(
              ORMap.delta(removed = Seq("LWWRegister-2"),
                          added = Seq("ORMap-1" -> ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+123)))))
            )
          )
          .send(
            delta(
              ORMap.delta(
                removed = Seq("ORSet-1"),
                updated = Seq("ORSet-2" -> ORSet.delta(added = Seq("a", "b", "c")),
                              "ORMap-1" -> ORMap.delta(added = Seq("LWWRegister-1" -> LWWRegister.delta("ABC"))))
              )
            )
          )
          .send(
            command(4,
                    id,
                    "Process",
                    Request(id,
                            ORMap.updateWith("ORMap-1" -> ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-456)))))
          )
          .expect(
            reply(
              4,
              ORMap.state(
                "LWWRegister-1" -> LWWRegister.state("ONE"),
                "ORMap-1" -> ORMap.state("LWWRegister-1" -> LWWRegister.state("ABC"),
                                         "PNCounter-1" -> PNCounter.state(-333)),
                "ORSet-2" -> ORSet.state("a", "b", "c"),
                "PNCounter-1" -> PNCounter.state(+42)
              ),
              ORMap.update(
                updated = Seq(
                  "ORMap-1" -> ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(-456)))
                )
              )
            ),
            Delta.sorted
          )
          .send(delta(ORMap.delta(cleared = true, added = Seq("GSet-1" -> GSet.delta("1", "2", "3")))))
          .send(
            command(
              5,
              id,
              "Process",
              Request(id,
                      ORMap.remove("PNCounter-1", "LWWRegister-1") ++
                      ORMap.updateWith("GSet-1" -> GSet.add("2", "4", "6")))
            )
          )
          .expect(
            reply(
              5,
              ORMap.state(
                "GSet-1" -> GSet.state("1", "2", "3", "4", "6")
              ),
              ORMap.update(
                updated = Seq(
                  "GSet-1" -> GSet.update("4", "6")
                )
              )
            ),
            Delta.sorted
          )
          .send(command(6, id, "Process", Request(id, ORMap.clear() ++ ORMap.add("GCounter-1"))))
          .expect(
            reply(6,
                  ORMap.state("GCounter-1" -> GCounter.state(0)),
                  ORMap.update(cleared = true, added = Seq("GCounter-1" -> GCounter.update(0))))
          )
          .send(delta(ORMap.delta(removed = Seq("GSet-1"), added = Seq("GCounter-1" -> GCounter.delta(42)))))
          .send(command(7, id, "Process", Request(id, ORMap.updateWith("GCounter-1" -> GCounter.incrementBy(7)))))
          .expect(
            reply(7,
                  ORMap.state("GCounter-1" -> GCounter.state(49)),
                  ORMap.update(updated = Seq("GCounter-1" -> GCounter.update(7))))
          )
          .passivate()
      }
      "verify ORMap rehydration after passivation" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+123)))))
          .send(command(1, id, "Process", Request(id, ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(+321)))))
          .expect(
            reply(1,
                  ORMap.state("PNCounter-1" -> PNCounter.state(+444)),
                  ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(+321))))
          )
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+444)))))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, ORMap.state("PNCounter-1" -> PNCounter.state(+444))))
          .send(delta(ORMap.delta(updated = Seq("PNCounter-1" -> PNCounter.delta(-42)))))
          .send(command(2, id, "Process", Request(id, ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-360)))))
          .expect(
            reply(2,
                  ORMap.state("PNCounter-1" -> PNCounter.state(+42)),
                  ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(-360))))
          )
          .passivate()
      }

      "verify ORMap delete action received from entity" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, ORMap.add("Flag-1"))))
          .expect(
            reply(1,
                  ORMap.state("Flag-1" -> Flag.state(false)),
                  ORMap.update(added = Seq("Flag-1" -> Flag.update(false))))
          )
          .send(command(2, id, "Process", Request(id, ORMap.updateWith("Flag-1" -> Flag.enable()) :+ requestDelete)))
          .expect(reply(2, ORMap.state("Flag-1" -> Flag.state(true)), deleteCrdt))
          .passivate()
      }

      "verify ORMap delete action sent to entity" in ORMap.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // Vote tests

      object Vote {
        def state(selfVote: Boolean, votesFor: Int, totalVoters: Int): Response =
          Response(Some(State(State.Value.Vote(VoteValue(selfVote, votesFor, totalVoters)))))

        def self(vote: Boolean): Seq[RequestAction] =
          Seq(requestUpdate(Update(Update.Update.Vote(VoteUpdate(vote)))))

        def update(selfVote: Boolean): Effects =
          Effects(stateAction = crdtUpdate(delta(selfVote)))

        def delta(selfVote: Boolean, votesFor: Int = 0, totalVoters: Int = 0): CrdtDelta.Delta.Vote =
          deltaVote(selfVote, votesFor, totalVoters)

        def test(run: String => Any): Unit = crdtTest("Vote")(run)
      }

      "verify Vote initial empty state" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
          .passivate()
      }

      "verify Vote state changes" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(1, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), Vote.update(true)))
          .send(command(2, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(2, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1)))
          .send(command(3, id, "Process", Request(id, Vote.self(false))))
          .expect(reply(3, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1), Vote.update(false)))
          .send(command(4, id, "Process", Request(id, Vote.self(false))))
          .expect(reply(4, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
          .passivate()
      }

      "verify Vote initial delta in init" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, Vote.delta(selfVote = true, votesFor = 2, totalVoters = 3)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = true, votesFor = 2, totalVoters = 3)))
          .send(command(2, id, "Process", Request(id, Vote.self(false))))
          .expect(reply(2, Vote.state(selfVote = false, votesFor = 1, totalVoters = 3), Vote.update(false)))
          .passivate()
      }

      "verify Vote initial empty state with replicated initial delta" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Vote.delta(selfVote = false, votesFor = 3, totalVoters = 5)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = false, votesFor = 3, totalVoters = 5)))
          .send(command(2, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(2, Vote.state(selfVote = true, votesFor = 4, totalVoters = 5), Vote.update(true)))
          .passivate()
      }

      "verify Vote mix of local and replicated state changes" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delta(Vote.delta(selfVote = false, votesFor = 2, totalVoters = 4)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = false, votesFor = 2, totalVoters = 4)))
          .send(command(2, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(2, Vote.state(selfVote = true, votesFor = 3, totalVoters = 4), Vote.update(true)))
          .send(delta(Vote.delta(selfVote = true, votesFor = 5, totalVoters = 7)))
          .send(delta(Vote.delta(selfVote = true, votesFor = 4, totalVoters = 6)))
          .send(command(3, id, "Process", Request(id, Vote.self(false))))
          .expect(reply(3, Vote.state(selfVote = false, votesFor = 3, totalVoters = 6), Vote.update(false)))
          .passivate()
      }

      "verify Vote rehydration after passivation" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
          .send(delta(Vote.delta(selfVote = false, votesFor = 2, totalVoters = 5)))
          .send(command(2, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(2, Vote.state(selfVote = true, votesFor = 3, totalVoters = 5), Vote.update(true)))
          .passivate()
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id, Vote.delta(selfVote = true, votesFor = 3, totalVoters = 5)))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Vote.state(selfVote = true, votesFor = 3, totalVoters = 5)))
          .send(command(2, id, "Process", Request(id, Vote.self(false))))
          .expect(reply(2, Vote.state(selfVote = false, votesFor = 2, totalVoters = 5), Vote.update(false)))
          .passivate()
      }

      "verify Vote delete action received from entity" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Vote.self(true))))
          .expect(reply(1, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), Vote.update(true)))
          .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
          .expect(reply(2, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), deleteCrdt))
          .passivate()
      }

      "verify Vote delete action sent to entity" in Vote.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(delete)
          .passivate()
      }

      // forward and side effect tests

      "verify forward to second service" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(forwardTo(s"X-$id")))))
          .expect(forwarded(1, s"X-$id"))
          .passivate()
      }

      "verify forward with state changes" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(-42) :+ forwardTo(s"X-$id"))))
          .expect(forwarded(1, s"X-$id", PNCounter.update(-42)))
          .passivate()
      }

      "verify reply with side effect to second service" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(s"X-$id")))))
          .expect(reply(1, GSet.state(), sideEffects(s"X-$id")))
          .passivate()
      }

      "verify synchronous side effect to second service" in ORSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(s"X-$id", synchronous = true)))))
          .expect(reply(1, ORSet.state(), synchronousSideEffects(s"X-$id")))
          .passivate()
      }

      "verify forward and side effect to second service" in LWWRegister.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(s"B-$id"), forwardTo(s"A-$id")))))
          .expect(forwarded(1, s"A-$id", LWWRegister.update("") ++ sideEffects(s"B-$id")))
          .passivate()
      }

      "verify reply with multiple side effects" in Flag.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
          .expect(reply(1, Flag.state(false), sideEffects("1", "2", "3")))
          .passivate()
      }

      "verify reply with multiple side effects and state changes" in ORMap.test { id =>
        val crdtActions = ORMap.add("PNCounter-1") ++ ORMap.updateWith("GSet-1" -> GSet.add("a", "b", "c"))
        val actions = crdtActions ++ sideEffectsTo("1", "2", "3")
        val expectedState = ORMap.state("GSet-1" -> GSet.state("a", "b", "c"), "PNCounter-1" -> PNCounter.state(0))
        val crdtUpdates = ORMap.update(
          added = Seq(
            "GSet-1" -> GSet.update("a", "b", "c"),
            "PNCounter-1" -> PNCounter.update(0)
          )
        )
        val effects = crdtUpdates ++ sideEffects("1", "2", "3")
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, actions)))
          .expect(reply(1, expectedState, effects), Delta.sorted)
          .passivate()
      }

      // failure tests

      "verify failure action" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(failure(1, "expected failure"))
          .passivate()
      }

      "verify connection after failure action" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+42))))
          .expect(reply(1, PNCounter.state(+42), PNCounter.update(+42)))
          .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(failure(2, "expected failure"))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, PNCounter.state(+42)))
          .passivate()
      }

      // streamed response tests

      "verify streamed responses" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "ProcessStreamed", StreamedRequest(id), streamed = true))
          .expect(reply(1, GCounter.state(0), Effects(streamed = true)))
          .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1))))
          .expect(reply(2, GCounter.state(1), GCounter.update(1)))
          .expect(streamed(1, GCounter.state(1)))
          .send(command(3, id, "Process", Request(id, GCounter.incrementBy(2))))
          .expect(reply(3, GCounter.state(3), GCounter.update(2)))
          .expect(streamed(1, GCounter.state(3)))
          .send(command(4, id, "Process", Request(id, GCounter.incrementBy(3))))
          .expect(reply(4, GCounter.state(6), GCounter.update(3)))
          .expect(streamed(1, GCounter.state(6)))
          .passivate()
      }

      "verify streamed responses with stream ending action" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(
            command(1, id, "ProcessStreamed", StreamedRequest(id, endState = GCounter.state(3).state), streamed = true)
          )
          .expect(reply(1, GCounter.state(0), Effects(streamed = true)))
          .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1))))
          .expect(reply(2, GCounter.state(1), GCounter.update(1)))
          .expect(streamed(1, GCounter.state(1)))
          .send(command(3, id, "Process", Request(id, GCounter.incrementBy(2))))
          .expect(reply(3, GCounter.state(3), GCounter.update(2)))
          .expect(streamed(1, GCounter.state(3), Effects(endStream = true)))
          .send(command(4, id, "Process", Request(id, GCounter.incrementBy(3))))
          .expect(reply(4, GCounter.state(6), GCounter.update(3)))
          .passivate()
      }

      "verify streamed responses with cancellation" in PNCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(
            command(
              1,
              id,
              "ProcessStreamed",
              StreamedRequest(id, cancelUpdate = Some(PNCounter.updateWith(-42))),
              streamed = true
            )
          )
          .expect(reply(1, PNCounter.state(0), Effects(streamed = true)))
          .send(command(2, id, "Process", Request(id, PNCounter.changeBy(+10))))
          .expect(reply(2, PNCounter.state(+10), PNCounter.update(+10)))
          .expect(streamed(1, PNCounter.state(+10)))
          .send(command(3, id, "Process", Request(id, PNCounter.changeBy(+20))))
          .expect(reply(3, PNCounter.state(+30), PNCounter.update(+20)))
          .expect(streamed(1, PNCounter.state(+30)))
          .send(crdtStreamCancelled(1, id))
          .expect(streamCancelledResponse(1, PNCounter.update(-42)))
          .send(command(4, id, "Process", Request(id)))
          .expect(reply(4, PNCounter.state(-12)))
          .send(command(5, id, "Process", Request(id, PNCounter.changeBy(+30))))
          .expect(reply(5, PNCounter.state(+18), PNCounter.update(+30)))
          .passivate()
      }

      "verify streamed responses with side effects" in GSet.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(
            command(
              1,
              id,
              "ProcessStreamed",
              StreamedRequest(id, effects = Seq(Effect("one"), Effect("two", synchronous = true))),
              streamed = true
            )
          )
          .expect(reply(1, GSet.state(), Effects(streamed = true)))
          .send(command(2, id, "Process", Request(id, GSet.add("a"))))
          .expect(reply(2, GSet.state("a"), GSet.update("a")))
          .expect(streamed(1, GSet.state("a"), sideEffects("one") ++ synchronousSideEffects("two")))
          .send(command(3, id, "Process", Request(id, GSet.add("b"))))
          .expect(reply(3, GSet.state("a", "b"), GSet.update("b")))
          .expect(streamed(1, GSet.state("a", "b"), sideEffects("one") ++ synchronousSideEffects("two")))
          .passivate()
      }

      // write consistency tests

      def incrementWith(increment: Long, writeConsistency: UpdateWriteConsistency): Seq[RequestAction] =
        Seq(requestUpdate(GCounter.updateWith(increment).withWriteConsistency(writeConsistency)))

      def updateWith(value: Long, writeConsistency: CrdtWriteConsistency): Effects =
        Effects(stateAction = crdtUpdate(GCounter.delta(value), writeConsistency))

      "verify write consistency can be configured" in GCounter.test { id =>
        protocol.crdt
          .connect()
          .send(init(CrdtTckModel.name, id))
          .send(command(1, id, "Process", Request(id, incrementWith(1, UpdateWriteConsistency.LOCAL))))
          .expect(reply(1, GCounter.state(1), updateWith(1, CrdtWriteConsistency.LOCAL)))
          .send(command(2, id, "Process", Request(id, incrementWith(2, UpdateWriteConsistency.MAJORITY))))
          .expect(reply(2, GCounter.state(3), updateWith(2, CrdtWriteConsistency.MAJORITY)))
          .send(command(3, id, "Process", Request(id, incrementWith(3, UpdateWriteConsistency.ALL))))
          .expect(reply(3, GCounter.state(6), updateWith(3, CrdtWriteConsistency.ALL)))
          .passivate()
      }
    }

    "verifying model test: event-sourced entities" must {
      import EventSourcedMessages._
      import io.cloudstate.tck.model.eventsourced._

      val ServiceTwo = EventSourcedTwo.name

      var entityId: Int = 0
      def nextEntityId(): String = { entityId += 1; s"entity:$entityId" }

      def eventSourcedTest(test: String => Any): Unit =
        testFor(EventSourcedTckModel, EventSourcedTwo)(test(nextEntityId()))

      def emitEvent(value: String): RequestAction =
        RequestAction(RequestAction.Action.Emit(Emit(value)))

      def emitEvents(values: String*): Seq[RequestAction] =
        values.map(emitEvent)

      def forwardTo(id: String): RequestAction =
        RequestAction(RequestAction.Action.Forward(Forward(id)))

      def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
        RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

      def sideEffectsTo(ids: String*): Seq[RequestAction] =
        ids.map(id => sideEffectTo(id, synchronous = false))

      def failWith(message: String): RequestAction =
        RequestAction(RequestAction.Action.Fail(Fail(message)))

      def persisted(value: String): ScalaPbAny =
        protobufAny(Persisted(value))

      def events(values: String*): Effects =
        Effects(events = values.map(persisted))

      def snapshotAndEvents(snapshotValue: String, eventValues: String*): Effects =
        events(eventValues: _*).withSnapshot(persisted(snapshotValue))

      def sideEffects(ids: String*): Effects =
        createSideEffects(synchronous = false, ids)

      def synchronousSideEffects(ids: String*): Effects =
        createSideEffects(synchronous = true, ids)

      def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
        ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }

      "verify initial empty state" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response()))
          .passivate()
      }

      "verify single emitted event" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A"))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("A")))
          .passivate()
      }

      "verify multiple emitted events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("ABC")))
          .passivate()
      }

      "verify multiple emitted events and snapshots" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A"))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id, emitEvents("B"))))
          .expect(reply(2, Response("AB"), events("B")))
          .send(command(3, id, "Process", Request(id, emitEvents("C"))))
          .expect(reply(3, Response("ABC"), events("C")))
          .send(command(4, id, "Process", Request(id, emitEvents("D"))))
          .expect(reply(4, Response("ABCD"), events("D")))
          .send(command(5, id, "Process", Request(id, emitEvents("E"))))
          .expect(reply(5, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
          .send(command(6, id, "Process", Request(id, emitEvents("F", "G", "H"))))
          .expect(reply(6, Response("ABCDEFGH"), events("F", "G", "H")))
          .send(command(7, id, "Process", Request(id, emitEvents("I", "J"))))
          .expect(reply(7, Response("ABCDEFGHIJ"), snapshotAndEvents("ABCDEFGHIJ", "I", "J")))
          .send(command(8, id, "Process", Request(id, emitEvents("K"))))
          .expect(reply(8, Response("ABCDEFGHIJK"), events("K")))
          .send(command(9, id, "Process", Request(id)))
          .expect(reply(9, Response("ABCDEFGHIJK")))
          .passivate()
      }

      "verify initial snapshot" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDE")))
          .passivate()
      }

      "verify initial snapshot and events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(event(6, persisted("F")))
          .send(event(7, persisted("G")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDEFG")))
          .passivate()
      }

      "verify rehydration after passivation" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, emitEvents("D", "E"))))
          .expect(reply(2, Response("ABCDE"), snapshotAndEvents("ABCDE", "D", "E")))
          .send(command(3, id, "Process", Request(id, emitEvents("F"))))
          .expect(reply(3, Response("ABCDEF"), events("F")))
          .send(command(4, id, "Process", Request(id, emitEvents("G"))))
          .expect(reply(4, Response("ABCDEFG"), events("G")))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(event(6, persisted("F")))
          .send(event(7, persisted("G")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDEFG")))
          .passivate()
      }

      "verify forward to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(forwardTo(id)))))
          .expect(forward(1, EventSourcedTwo.name, "Call", Request(id)))
          .passivate()
      }

      "verify forward with emitted events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(emitEvent("A"), forwardTo(id)))))
          .expect(forward(1, EventSourcedTwo.name, "Call", Request(id), events("A")))
          .passivate()
      }

      "verify forward with emitted events and snapshot" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("D"), emitEvent("E"), forwardTo(id)))))
          .expect(forward(2, EventSourcedTwo.name, "Call", Request(id), snapshotAndEvents("ABCDE", "D", "E")))
          .passivate()
      }

      "verify reply with side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id)))))
          .expect(reply(1, Response(), sideEffects(id)))
          .passivate()
      }

      "verify synchronous side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id, synchronous = true)))))
          .expect(reply(1, Response(), synchronousSideEffects(id)))
          .passivate()
      }

      "verify forward and side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), forwardTo(id)))))
          .expect(forward(1, ServiceTwo, "Call", Request(id), sideEffects(id)))
          .passivate()
      }

      "verify reply with multiple side effects" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
          .expect(reply(1, Response(), sideEffects("1", "2", "3")))
          .passivate()
      }

      "verify reply with multiple side effects, events, and snapshot" in eventSourcedTest { id =>
        val actions = emitEvents("A", "B", "C", "D", "E") ++ sideEffectsTo("1", "2", "3")
        val effects = snapshotAndEvents("ABCDE", "A", "B", "C", "D", "E") ++ sideEffects("1", "2", "3")
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, actions)))
          .expect(reply(1, Response("ABCDE"), effects))
          .passivate()
      }

      "verify failure action" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }

      "verify connection after failure action" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(emitEvent("A")))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(2, "expected failure"))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Response("A")))
          .passivate()
      }

      "verify failure actions do not retain emitted events, by requesting entity restart" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("4"), emitEvent("5"), failWith("failure 1")))))
          .expect(actionFailure(2, "failure 1", restart = true))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(event(1, persisted("A")))
          .send(event(2, persisted("B")))
          .send(event(3, persisted("C")))
          .send(command(1, id, "Process", Request(id, emitEvents("D"))))
          .expect(reply(1, Response("ABCD"), events("D")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("6"), failWith("failure 2"), emitEvent("7")))))
          .expect(actionFailure(2, "failure 2", restart = true))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(event(1, persisted("A")))
          .send(event(2, persisted("B")))
          .send(event(3, persisted("C")))
          .send(event(4, persisted("D")))
          .send(command(1, id, "Process", Request(id, emitEvents("E"))))
          .expect(reply(1, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
          .passivate()
      }

      "verify failure actions do not allow side effects" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }
    }

    // TODO convert this into a ScalaCheck generated test case
    "verifying app test: event-sourced shopping cart" must {
      import com.example.shoppingcart.shoppingcart._
      import EventSourcedMessages._
      import EventSourcedShoppingCartVerifier._

      def verifyGetInitialEmptyCart(session: EventSourcedShoppingCartVerifier, cartId: String): Unit = {
        eventSourcedShoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe Cart()
        session.verifyConnection()
        session.verifyGetInitialEmptyCart(cartId)
      }

      def verifyGetCart(session: EventSourcedShoppingCartVerifier, cartId: String, expected: Item*): Unit = {
        val expectedCart = shoppingCart(expected: _*)
        eventSourcedShoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe expectedCart
        session.verifyGetCart(cartId, expectedCart)
      }

      def verifyAddItem(session: EventSourcedShoppingCartVerifier, cartId: String, item: Item): Unit = {
        val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
        eventSourcedShoppingCartClient.addItem(addLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyAddItem(cartId, item)
      }

      def verifyRemoveItem(session: EventSourcedShoppingCartVerifier, cartId: String, itemId: String): Unit = {
        val removeLineItem = RemoveLineItem(cartId, itemId)
        eventSourcedShoppingCartClient.removeItem(removeLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyRemoveItem(cartId, itemId)
      }

      def verifyAddItemFailure(session: EventSourcedShoppingCartVerifier, cartId: String, item: Item): Unit = {
        val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
        val error = eventSourcedShoppingCartClient.addItem(addLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyAddItemFailure(cartId, item, description)
      }

      def verifyRemoveItemFailure(session: EventSourcedShoppingCartVerifier, cartId: String, itemId: String): Unit = {
        val removeLineItem = RemoveLineItem(cartId, itemId)
        val error = eventSourcedShoppingCartClient.removeItem(removeLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyRemoveItemFailure(cartId, itemId, description)
      }

      "verify get cart, add item, remove item, and failures" in testFor(ShoppingCart) {
        val session = shoppingCartSession(interceptor)
        verifyGetInitialEmptyCart(session, "cart:1") // initial empty state
        verifyAddItem(session, "cart:1", Item("product:1", "Product1", 1)) // add the first product
        verifyAddItem(session, "cart:1", Item("product:2", "Product2", 2)) // add the second product
        verifyAddItem(session, "cart:1", Item("product:1", "Product1", 11)) // increase first product
        verifyAddItem(session, "cart:1", Item("product:2", "Product2", 31)) // increase second product
        verifyGetCart(session, "cart:1", Item("product:1", "Product1", 12), Item("product:2", "Product2", 33)) // check state
        verifyRemoveItem(session, "cart:1", "product:1") // remove first product
        verifyAddItemFailure(session, "cart:1", Item("product:2", "Product2", -7)) // add negative quantity
        verifyAddItemFailure(session, "cart:1", Item("product:1", "Product1", 0)) // add zero quantity
        verifyRemoveItemFailure(session, "cart:1", "product:1") // remove non-existing product
        verifyGetCart(session, "cart:1", Item("product:2", "Product2", 33)) // check final state
      }
    }

    "verifying proxy test: gRPC ServerReflection API" must {
      "verify that the proxy supports server reflection" in {
        import grpc.reflection.v1alpha.reflection._
        import ServerReflectionRequest.{MessageRequest => In}
        import ServerReflectionResponse.{MessageResponse => Out}

        val expectedServices = Seq(ServerReflection.name) ++ enabledServices.sorted

        val connection = client.serverReflection.connect()

        connection.sendAndExpect(
          In.ListServices(""),
          Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
        )

        connection.sendAndExpect(
          In.ListServices("nonsense.blabla."),
          Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
        )

        connection.sendAndExpect(
          In.FileContainingSymbol("nonsense.blabla.Void"),
          Out.FileDescriptorResponse(FileDescriptorResponse(Nil))
        )

        connection.close()
      }
    }

    "verifying proxy test: HTTP API" must {
      "verify the HTTP API for event-sourced ShoppingCart service" in testFor(EventSourcedShoppingCart) {
        import EventSourcedShoppingCartVerifier._

        def checkHttpRequest(path: String, body: String = null)(expected: => String): Unit = {
          val response = client.http.request(path, body)
          val expectedResponse = expected
          response.futureValue mustBe expectedResponse
        }

        val session = shoppingCartSession(interceptor)

        checkHttpRequest("carts/foo") {
          session.verifyConnection()
          session.verifyGetInitialEmptyCart("foo")
          """{"items":[]}"""
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 5}""") {
          session.verifyAddItem("foo", Item("A14362347", "Deluxe", 5))
          "{}"
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "B14623482", "name": "Basic", "quantity": 1}""") {
          session.verifyAddItem("foo", Item("B14623482", "Basic", 1))
          "{}"
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 2}""") {
          session.verifyAddItem("foo", Item("A14362347", "Deluxe", 2))
          "{}"
        }

        checkHttpRequest("carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
          """{"items":[{"productId":"A14362347","name":"Deluxe","quantity":7},{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        }

        checkHttpRequest("carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
          """[{"productId":"A14362347","name":"Deluxe","quantity":7.0},{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        }

        checkHttpRequest("cart/foo/items/A14362347/remove", "") {
          session.verifyRemoveItem("foo", "A14362347")
          "{}"
        }

        checkHttpRequest("carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """{"items":[{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        }

        checkHttpRequest("carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """[{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        }
      }

      "verify the HTTP API for value-based ShoppingCart service" in testFor(ValueEntityShoppingCart) {
        import ValueEntityShoppingCartVerifier._

        def checkHttpRequest(path: String, body: String = null)(expected: => String): Unit = {
          val response = client.http.request(path, body)
          val expectedResponse = expected
          response.futureValue mustBe expectedResponse
        }

        val session = shoppingCartSession(interceptor)

        checkHttpRequest("ve/carts/foo") {
          session.verifyConnection()
          session.verifyGetInitialEmptyCart("foo")
          """{"items":[]}"""
        }

        checkHttpRequest("ve/cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 5}""") {
          session.verifyAddItem("foo", Item("A14362347", "Deluxe", 5), Cart(Item("A14362347", "Deluxe", 5)))
          "{}"
        }

        checkHttpRequest("ve/cart/foo/items/add", """{"productId": "B14623482", "name": "Basic", "quantity": 1}""") {
          session.verifyAddItem("foo",
                                Item("B14623482", "Basic", 1),
                                Cart(Item("A14362347", "Deluxe", 5), Item("B14623482", "Basic", 1)))
          "{}"
        }

        checkHttpRequest("ve/cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 2}""") {
          session.verifyAddItem("foo",
                                Item("A14362347", "Deluxe", 2),
                                Cart(Item("B14623482", "Basic", 1), Item("A14362347", "Deluxe", 7)))
          "{}"
        }

        checkHttpRequest("ve/carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1), Item("A14362347", "Deluxe", 7)))
          """{"items":[{"productId":"B14623482","name":"Basic","quantity":1},{"productId":"A14362347","name":"Deluxe","quantity":7}]}"""
        }

        checkHttpRequest("ve/carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1), Item("A14362347", "Deluxe", 7)))
          """[{"productId":"B14623482","name":"Basic","quantity":1.0},{"productId":"A14362347","name":"Deluxe","quantity":7.0}]"""
        }

        checkHttpRequest("ve/cart/foo/items/A14362347/remove", "") {
          session.verifyRemoveItem("foo", "A14362347", Cart(Item("B14623482", "Basic", 1)))
          "{}"
        }

        checkHttpRequest("ve/carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """{"items":[{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        }

        checkHttpRequest("ve/carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """[{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        }

        checkHttpRequest("ve/carts/foo/remove", """{"userId": "foo"}""") {
          session.verifyRemoveCart("foo")
          "{}"
        }

        checkHttpRequest("ve/carts/foo") {
          session.verifyGetCart("foo", shoppingCart())
          """{"items":[]}"""
        }
      }
    }

    "verifying model test: value-based entities" must {
      import ValueEntityMessages._
      import io.cloudstate.tck.model.valueentity.valueentity._

      val ServiceTwo = ValueEntityTwo.name

      var entityId: Int = 0
      def nextEntityId(): String = { entityId += 1; s"entity:$entityId" }

      def valueEntityTest(test: String => Any): Unit =
        testFor(ValueEntityTckModel, ValueEntityTwo)(test(nextEntityId()))

      def updateState(value: String): RequestAction =
        RequestAction(RequestAction.Action.Update(Update(value)))

      def updateStates(values: String*): Seq[RequestAction] =
        values.map(updateState)

      def deleteState(): RequestAction =
        RequestAction(RequestAction.Action.Delete(Delete()))

      def updateAndDeleteActions(values: String*): Seq[RequestAction] =
        values.map(updateState) :+ deleteState()

      def deleteBetweenUpdateActions(first: String, second: String): Seq[RequestAction] =
        Seq(updateState(first), deleteState(), updateState(second))

      def forwardTo(id: String): RequestAction =
        RequestAction(RequestAction.Action.Forward(Forward(id)))

      def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
        RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

      def sideEffectsTo(ids: String*): Seq[RequestAction] =
        ids.map(id => sideEffectTo(id))

      def failWith(message: String): RequestAction =
        RequestAction(RequestAction.Action.Fail(Fail(message)))

      def persisted(value: String): ScalaPbAny =
        protobufAny(Persisted(value))

      def update(value: String): Effects =
        Effects.empty.withUpdateAction(persisted(value))

      def delete(): Effects =
        Effects.empty.withDeleteAction()

      def sideEffects(ids: String*): Effects =
        createSideEffects(synchronous = false, ids)

      def synchronousSideEffects(ids: String*): Effects =
        createSideEffects(synchronous = true, ids)

      def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
        ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }

      "verify initial empty state" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response()))
          .passivate()
      }

      "verify update state" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, updateStates("A"))))
          .expect(reply(1, Response("A"), update("A")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("A")))
          .passivate()
      }

      "verify delete state" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, updateStates("A"))))
          .expect(reply(1, Response("A"), update("A")))
          .send(command(2, id, "Process", Request(id, Seq(deleteState()))))
          .expect(reply(2, Response(), delete()))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Response()))
          .passivate()
      }

      "verify sub invocations with multiple update states" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, updateStates("A", "B", "C"))))
          .expect(reply(1, Response("C"), update("C")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("C")))
          .passivate()
      }

      "verify sub invocations with multiple update states and delete states" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, updateAndDeleteActions("A", "B"))))
          .expect(reply(1, Response(), delete()))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response()))
          .passivate()
      }

      "verify sub invocations with update, delete and update states" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, deleteBetweenUpdateActions("A", "B"))))
          .expect(reply(1, Response("B"), update("B")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("B")))
          .passivate()
      }

      "verify rehydration after passivation" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, updateStates("A"))))
          .expect(reply(1, Response("A"), update("A")))
          .send(command(2, id, "Process", Request(id, updateStates("B"))))
          .expect(reply(2, Response("B"), update("B")))
          .send(command(3, id, "Process", Request(id, updateStates("C"))))
          .expect(reply(3, Response("C"), update("C")))
          .send(command(4, id, "Process", Request(id, updateStates("D"))))
          .expect(reply(4, Response("D"), update("D")))
          .passivate()
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id, state(persisted("D"))))
          .send(command(1, id, "Process", Request(id, updateStates("E"))))
          .expect(reply(1, Response("E"), update("E")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("E")))
          .passivate()
      }

      "verify reply with multiple side effects" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
          .expect(reply(1, Response(), sideEffects("1", "2", "3")))
          .passivate()
      }

      "verify reply with side effect to second service" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id)))))
          .expect(reply(1, Response(), sideEffects(id)))
          .passivate()
      }

      "verify reply with multiple side effects and state" in valueEntityTest { id =>
        val actions = updateStates("A", "B", "C", "D", "E") ++ sideEffectsTo("1", "2", "3")
        val effects = sideEffects("1", "2", "3").withUpdateAction(persisted("E"))
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, actions)))
          .expect(reply(1, Response("E"), effects))
          .passivate()
      }

      "verify synchronous side effect to second service" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id, synchronous = true)))))
          .expect(reply(1, Response(), synchronousSideEffects(id)))
          .passivate()
      }

      "verify forward to second service" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(forwardTo(id)))))
          .expect(forward(1, ServiceTwo, "Call", Request(id)))
          .passivate()
      }

      "verify forward with updated state to second service" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(updateState("A"), forwardTo(id)))))
          .expect(forward(1, ServiceTwo, "Call", Request(id), update("A")))
          .passivate()
      }

      "verify forward and side effect to second service" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), forwardTo(id)))))
          .expect(forward(1, ServiceTwo, "Call", Request(id), sideEffects(id)))
          .passivate()
      }

      "verify failure action" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }

      "verify connection after failure action" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(updateState("A")))))
          .expect(reply(1, Response("A"), update("A")))
          .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(2, "expected failure"))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Response("A")))
          .passivate()
      }

      "verify failure action do not allow side effects" in valueEntityTest { id =>
        protocol.valueEntity
          .connect()
          .send(init(ValueEntityTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }
    }

    "verifying app test: value-based entity shopping cart" must {
      import com.example.valueentity.shoppingcart.shoppingcart.{
        AddLineItem => ValueEntityAddLineItem,
        Cart => ValueEntityCart,
        GetShoppingCart => ValueEntityGetShoppingCart,
        RemoveLineItem => ValueEntityRemoveLineItem
      }
      import ValueEntityMessages._
      import ValueEntityShoppingCartVerifier._

      def verifyGetInitialEmptyCart(session: ValueEntityShoppingCartVerifier, cartId: String): Unit = {
        valueEntityShoppingCartClient.getCart(ValueEntityGetShoppingCart(cartId)).futureValue mustBe ValueEntityCart()
        session.verifyConnection()
        session.verifyGetInitialEmptyCart(cartId)
      }

      def verifyGetCart(session: ValueEntityShoppingCartVerifier, cartId: String, expected: Item*): Unit = {
        val expectedCart = shoppingCart(expected: _*)
        valueEntityShoppingCartClient.getCart(ValueEntityGetShoppingCart(cartId)).futureValue mustBe expectedCart
        session.verifyGetCart(cartId, expectedCart)
      }

      def verifyAddItem(session: ValueEntityShoppingCartVerifier, cartId: String, item: Item, expected: Cart): Unit = {
        val addLineItem = ValueEntityAddLineItem(cartId, item.id, item.name, item.quantity)
        valueEntityShoppingCartClient.addItem(addLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyAddItem(cartId, item, expected)
      }

      def verifyRemoveItem(session: ValueEntityShoppingCartVerifier,
                           cartId: String,
                           itemId: String,
                           expected: Cart): Unit = {
        val removeLineItem = ValueEntityRemoveLineItem(cartId, itemId)
        valueEntityShoppingCartClient.removeItem(removeLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyRemoveItem(cartId, itemId, expected)
      }

      def verifyAddItemFailure(session: ValueEntityShoppingCartVerifier, cartId: String, item: Item): Unit = {
        val addLineItem = ValueEntityAddLineItem(cartId, item.id, item.name, item.quantity)
        val error = valueEntityShoppingCartClient.addItem(addLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyAddItemFailure(cartId, item, description)
      }

      def verifyRemoveItemFailure(session: ValueEntityShoppingCartVerifier, cartId: String, itemId: String): Unit = {
        val removeLineItem = ValueEntityRemoveLineItem(cartId, itemId)
        val error = valueEntityShoppingCartClient.removeItem(removeLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyRemoveItemFailure(cartId, itemId, description)
      }

      "verify get cart, add item, remove item, and failures" in testFor(ValueEntityShoppingCart) {
        val session = shoppingCartSession(interceptor)
        verifyGetInitialEmptyCart(session, "cart:1") // initial empty state

        // add the first product and pass the expected state
        verifyAddItem(session, "cart:1", Item("product:1", "Product1", 1), Cart(Item("product:1", "Product1", 1)))

        // add the second product and pass the expected state
        verifyAddItem(
          session,
          "cart:1",
          Item("product:2", "Product2", 2),
          Cart(Item("product:1", "Product1", 1), Item("product:2", "Product2", 2))
        )

        // increase first product and pass the expected state
        verifyAddItem(
          session,
          "cart:1",
          Item("product:1", "Product1", 11),
          Cart(Item("product:2", "Product2", 2), Item("product:1", "Product1", 12))
        )

        // increase second product and pass the expected state
        verifyAddItem(
          session,
          "cart:1",
          Item("product:2", "Product2", 31),
          Cart(Item("product:1", "Product1", 12), Item("product:2", "Product2", 33))
        )

        verifyGetCart(session, "cart:1", Item("product:1", "Product1", 12), Item("product:2", "Product2", 33)) // check state

        // remove first product and pass the expected state
        verifyRemoveItem(session, "cart:1", "product:1", Cart(Item("product:2", "Product2", 33)))
        verifyAddItemFailure(session, "cart:1", Item("product:2", "Product2", -7)) // add negative quantity
        verifyAddItemFailure(session, "cart:1", Item("product:1", "Product1", 0)) // add zero quantity
        verifyRemoveItemFailure(session, "cart:1", "product:1") // remove non-existing product
        verifyGetCart(session, "cart:1", Item("product:2", "Product2", 33)) // check final state
      }
    }
  }
}
