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

package io.cloudstate.proxy.crdt

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import com.google.protobuf.empty.Empty
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.entity._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object AbstractCrdtEntitySpec {
  def config: Config = ConfigFactory.parseString("""
      |akka.actor.provider = cluster
      |# Make the tests run faster
      |akka.cluster.distributed-data.notify-subscribers-interval = 50ms
      |akka.actor.allow-java-serialization = on
      |akka.actor.warn-about-java-serializer-usage = off
      |akka.remote {
      |  enabled-transports = []
      |  artery {
      |    enabled = on
      |    transport = tcp
      |    canonical.port = 0
      |  }
      |}""".stripMargin)

  final val ServiceName = "some.ServiceName"
  final val UserFunctionName = "user-function-name"
  final case object StreamClosed

  // Some useful anys
  final val command = ProtoAny("command", ByteString.copyFromUtf8("foo"))
  final val element1 = ProtoAny("element1", ByteString.copyFromUtf8("1"))
  final val element2 = ProtoAny("element2", ByteString.copyFromUtf8("2"))
  final val element3 = ProtoAny("element3", ByteString.copyFromUtf8("3"))
}

abstract class AbstractCrdtEntitySpec
    extends TestKit(ActorSystem("test", AbstractCrdtEntitySpec.config))
    with WordSpecLike
    with Matchers
    with Inside
    with Eventually
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ImplicitSender
    with OptionValues {

  import AbstractCrdtEntitySpec._

  private val ddata = DistributedData(system)
  protected implicit val selfUniqueAddress: SelfUniqueAddress = ddata.selfUniqueAddress
  protected implicit val mat: Materializer = ActorMaterializer()
  private val cluster = Cluster(system)

  // These are set and read in the entities
  @volatile protected var toUserFunction: TestProbe = _
  @volatile protected var entityDiscovery: TestProbe = _
  @volatile protected var fromUserFunction: ActorRef = _
  protected var entity: ActorRef = _

  // Incremented for each test by before() callback
  private var idSeq = 0

  protected type T <: ReplicatedData
  protected type S
  protected type D

  protected def key(name: String): Key[T]
  protected def initial: T
  protected def extractState(state: CrdtState.State): S
  protected def extractDelta(delta: CrdtDelta.Delta): D

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(3, Seconds), Span(200, Millis))

  protected def keyName: String = UserFunctionName + "-" + entityId
  protected def entityId: String = "entity" + idSeq.toString

  protected def get(): T = {
    val k = key(keyName)
    ddata.replicator ! Get(k, ReadLocal)
    expectMsgType[GetSuccess[_]].get(k)
  }

  protected def update(modify: T => T): Unit = {
    val k = key(keyName)
    ddata.replicator ! Update(k, initial, WriteLocal)(modify)
    expectMsgType[UpdateSuccess[_]]
  }

  protected def sendAndExpectCommand(name: String, payload: ProtoAny): Long = {
    entity ! EntityCommand(entityId, name, Some(payload))
    expectCommand(name, payload)
  }

  protected def sendAndExpectStreamedCommand(name: String, payload: ProtoAny): (Long, TestProbe) = {
    entity ! EntityCommand(entityId, name, Some(payload), true)
    val source = expectMsgType[Source[UserFunctionReply, NotUsed]]
    val streamProbe = TestProbe()
    watch(streamProbe.testActor)
    source.runWith(Sink.actorRef(streamProbe.testActor, PoisonPill))

    (expectCommand(name, payload, true), streamProbe)
  }

  protected def expectState(): S =
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.State(state) => extractState(state.state)
    }

  protected def expectDelta(): D =
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.Changed(delta) => extractDelta(delta.delta)
    }

  protected def sendAndExpectReply(
      commandId: Long,
      action: CrdtStateAction.Action = CrdtStateAction.Action.Empty,
      writeConsistency: CrdtWriteConsistency = CrdtWriteConsistency.LOCAL
  ): UserFunctionReply = {
    val reply = doSendAndExpectReply(commandId, action, writeConsistency)
    reply.clientAction shouldBe None
    reply
  }

  protected def sendStreamedMessage(commandId: Long,
                                    payload: Option[ProtoAny] = None,
                                    endStream: Boolean = false): Unit =
    fromUserFunction ! CrdtStreamOut(
      CrdtStreamOut.Message.StreamedMessage(
        CrdtStreamedMessage(commandId = commandId,
                            sideEffects = Nil,
                            clientAction = payload.map(p => ClientAction(ClientAction.Action.Reply(Reply(Some(p))))),
                            endStream = endStream)
      )
    )

  protected def sendAndExpectFailure(commandId: Long,
                                     action: CrdtStateAction.Action = CrdtStateAction.Action.Empty,
                                     writeConsistency: CrdtWriteConsistency = CrdtWriteConsistency.LOCAL): Failure = {
    val reply = doSendAndExpectReply(commandId, action, writeConsistency)
    inside(reply.clientAction) {
      case Some(ClientAction(ClientAction.Action.Failure(failure), _)) => failure
    }
  }

  protected def sendReply(commandId: Long,
                          action: CrdtStateAction.Action = CrdtStateAction.Action.Empty,
                          writeConsistency: CrdtWriteConsistency = CrdtWriteConsistency.LOCAL,
                          streamed: Boolean = false) =
    fromUserFunction ! CrdtStreamOut(
      CrdtStreamOut.Message.Reply(
        CrdtReply(
          commandId = commandId,
          sideEffects = Nil,
          clientAction = None,
          stateAction = Some(CrdtStateAction(action = action, writeConsistency = writeConsistency)),
          streamed = streamed
        )
      )
    )

  protected def doSendAndExpectReply(commandId: Long,
                                     action: CrdtStateAction.Action,
                                     writeConsistency: CrdtWriteConsistency): UserFunctionReply = {
    sendReply(commandId, action, writeConsistency)
    expectMsgType[UserFunctionReply]
  }

  protected def expectCommand(name: String, payload: ProtoAny, streamed: Boolean = false): Long =
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.Command(Command(eid, cid, n, p, s, _, _)) =>
        eid should ===(entityId)
        n should ===(name)
        p shouldBe Some(payload)
        s shouldBe streamed
        cid
    }

  protected def createAndExpectInit(): Option[S] = {
    toUserFunction = TestProbe()
    entityDiscovery = TestProbe()
    entity = system.actorOf(
      CrdtEntity.props(
        new Crdt() {
          override def handle(in: Source[CrdtStreamIn, NotUsed]): Source[CrdtStreamOut, NotUsed] = {
            in.runWith(Sink.actorRef(toUserFunction.testActor, StreamClosed))
            Source.actorRef(100, OverflowStrategy.fail).mapMaterializedValue { actor =>
              fromUserFunction = actor
              NotUsed
            }
          }
        },
        CrdtEntity.Configuration(ServiceName, UserFunctionName, Timeout(10.minutes), 100, 10.minutes, 10.minutes),
        new EntityDiscovery {
          override def discover(in: ProxyInfo): Future[EntitySpec] = {
            entityDiscovery.testActor ! in
            Future.failed(new Exception("Not expecting discover"))
          }
          override def reportError(in: UserFunctionError): Future[Empty] = {
            entityDiscovery.testActor ! in
            Future.successful(Empty())
          }
        }
      ),
      entityId
    )

    val init = toUserFunction.expectMsgType[CrdtStreamIn]
    inside(init.message) {
      case CrdtStreamIn.Message.Init(CrdtInit(serviceName, eid, state, _)) =>
        serviceName should ===(ServiceName)
        eid should ===(entityId)
        state.map(s => extractState(s.state))
    }
  }

  before {
    idSeq += 1
  }

  after {
    if (entity != null) {
      entity ! PoisonPill
      entity = null
      toUserFunction.testActor ! PoisonPill
      toUserFunction = null
      entityDiscovery.testActor ! PoisonPill
      entityDiscovery = null
      fromUserFunction = null
    }
  }

  override protected def beforeAll(): Unit =
    cluster.join(cluster.selfAddress)

  override protected def afterAll(): Unit =
    Await.ready(system.terminate(), 10.seconds)

}
