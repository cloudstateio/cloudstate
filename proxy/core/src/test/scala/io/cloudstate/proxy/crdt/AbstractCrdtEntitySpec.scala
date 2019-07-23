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
import io.cloudstate.crdt._
import io.cloudstate.entity._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object AbstractCrdtEntitySpec {
  def config: Config = ConfigFactory.parseString(
    """
      |akka.actor.provider = cluster
      |# Make the tests run faster
      |akka.cluster.distributed-data.notify-subscribers-interval = 50ms
      |akka.remote.netty.tcp.port = 0
    """.stripMargin)

  final val ServiceName = "some.ServiceName"
  final val UserFunctionName = "user-function-name"
  final case object StreamClosed

  // Some useful anys
  final val command = ProtoAny("command", ByteString.copyFromUtf8("foo"))
  final val element1 = ProtoAny("element1", ByteString.copyFromUtf8("1"))
  final val element2 = ProtoAny("element2", ByteString.copyFromUtf8("2"))
  final val element3 = ProtoAny("element3", ByteString.copyFromUtf8("3"))
}

abstract class AbstractCrdtEntitySpec extends TestKit(ActorSystem("test", AbstractCrdtEntitySpec.config))
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
  private implicit val mat: Materializer = ActorMaterializer()
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

  protected def expectState(): S = {
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.State(state) => extractState(state.state)
    }
  }

  protected def expectDelta(): D = {
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.Changed(delta) => extractDelta(delta.delta)
    }
  }

  protected def sendAndExpectReply(commandId: Long, action: CrdtReply.Action = CrdtReply.Action.Empty,
    writeConsistency: CrdtReply.WriteConsistency = CrdtReply.WriteConsistency.LOCAL): UserFunctionReply = {
    val reply = doSendAndExpectReply(commandId, action, writeConsistency)
    inside(reply.message) {
      case UserFunctionReply.Message.Empty => reply
    }
  }

  protected def sendAndExpectFailure(commandId: Long, action: CrdtReply.Action = CrdtReply.Action.Empty,
    writeConsistency: CrdtReply.WriteConsistency = CrdtReply.WriteConsistency.LOCAL): Failure = {
    val reply = doSendAndExpectReply(commandId, action, writeConsistency)
    inside(reply.message) {
      case UserFunctionReply.Message.Failure(failure) => failure
    }
  }

  protected def doSendAndExpectReply(commandId: Long, action: CrdtReply.Action, writeConsistency: CrdtReply.WriteConsistency): UserFunctionReply = {
    fromUserFunction ! CrdtStreamOut(CrdtStreamOut.Message.Reply(CrdtReply(commandId = commandId, sideEffects = Nil,
      writeConsistency = writeConsistency, response = CrdtReply.Response.Empty,
      action = action)))

    expectMsgType[UserFunctionReply]
  }

  protected def expectCommand(name: String, payload: ProtoAny): Long = {
    inside(toUserFunction.expectMsgType[CrdtStreamIn].message) {
      case CrdtStreamIn.Message.Command(Command(eid, cid, n, p)) =>
        eid should ===(entityId)
        n should ===(name)
        p shouldBe Some(payload)
        cid
    }
  }

  protected def createAndExpectInit(): Option[S] = {
    toUserFunction = TestProbe()
    entityDiscovery = TestProbe()
    entity = system.actorOf(CrdtEntity.props(new Crdt() {
      override def handle(in: Source[CrdtStreamIn, NotUsed]): Source[CrdtStreamOut, NotUsed] = {
        in.runWith(Sink.actorRef(toUserFunction.testActor, StreamClosed))
        Source.actorRef(100, OverflowStrategy.fail).mapMaterializedValue { actor =>
          fromUserFunction = actor
          NotUsed
        }
      }
    }, CrdtEntity.Configuration(ServiceName, UserFunctionName, Timeout(10.minutes), 100, 10.minutes, 10.minutes),
      new EntityDiscovery {
        override def discover(in: ProxyInfo): Future[EntitySpec] = {
          entityDiscovery.testActor ! in
          Future.failed(new Exception("Not expecting discover"))
        }
        override def reportError(in: UserFunctionError): Future[Empty] = {
          entityDiscovery.testActor ! in
          Future.successful(Empty())
        }
      }), entityId)

    val init = toUserFunction.expectMsgType[CrdtStreamIn]
    inside(init.message) {
      case CrdtStreamIn.Message.Init(CrdtInit(serviceName, eid, state)) =>
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

  override protected def beforeAll(): Unit = {
    cluster.join(cluster.selfAddress)
  }

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), 10.seconds)
  }


}
