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

package io.cloudstate.proxy.crud

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.entity.{ClientAction, Command, Failure}
import io.cloudstate.proxy.ConcurrencyEnforcer
import io.cloudstate.proxy.ConcurrencyEnforcer.ConcurrencyEnforcerSettings
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.duration._

object AbstractCrudEntitySpec {

  def config: Config =
    ConfigFactory
      .parseString("""
                     | # use in-memory journal for testing
                     | cloudstate.proxy.journal-enabled = true
                     | akka.persistence {
                     |   journal.plugin = "akka.persistence.journal.inmem"
                     |   snapshot-store.plugin = inmem-snapshot-store
                     | }
                     | inmem-snapshot-store.class = "io.cloudstate.proxy.crud.InMemSnapshotStore"
      """.stripMargin)

  final val ServiceName = "some.ServiceName"
  final val UserFunctionName = "crud-user-function-name"

  // Some useful anys
  final val command = ProtoAny("command", ByteString.copyFromUtf8("foo"))
  final val state1 = ProtoAny("state", ByteString.copyFromUtf8("state1"))
  final val state2 = ProtoAny("state", ByteString.copyFromUtf8("state2"))

}

abstract class AbstractCrudEntitySpec
    extends TestKit(ActorSystem("CrudEntityTest", AbstractCrudEntitySpec.config))
    with WordSpecLike
    with Matchers
    with Inside
    with Eventually
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ImplicitSender
    with OptionValues {

  import AbstractCrudEntitySpec._

  // These are set and read in the entities
  @volatile protected var userFunction: TestProbe = _
  @volatile private var statsCollector: TestProbe = _
  @volatile private var concurrencyEnforcer: ActorRef = _

  protected var entity: ActorRef = _
  protected var reactivatedEntity: ActorRef = _

  // Incremented for each test by before() callback
  private var idSeq = 0

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(200, Millis))

  protected def entityId: String = "entity" + idSeq.toString

  protected def sendAndExpectCommand(name: String, payload: ProtoAny, dest: ActorRef = entity): Long = {
    dest ! EntityCommand(entityId, name, Some(payload))
    expectCommand(name, payload)
  }

  private def expectCommand(name: String, payload: ProtoAny): Long =
    inside(userFunction.expectMsgType[CrudStreamIn].message) {
      case CrudStreamIn.Message.Command(Command(eid, cid, n, p, s, _, _)) =>
        eid should ===(entityId)
        n should ===(name)
        p shouldBe Some(payload)
        s shouldBe false
        cid
    }

  protected def sendAndExpectReply(commandId: Long,
                                   action: Option[CrudAction.Action] = None,
                                   dest: ActorRef = entity): UserFunctionReply = {
    sendReply(commandId, action, dest)
    val reply = expectMsgType[UserFunctionReply]
    reply.clientAction shouldBe None
    reply
  }

  protected def sendReply(commandId: Long, action: Option[CrudAction.Action] = None, dest: ActorRef = entity) =
    dest ! CrudStreamOut(
      CrudStreamOut.Message.Reply(
        CrudReply(
          commandId = commandId,
          sideEffects = Nil,
          clientAction = None,
          crudAction = action.map(a => CrudAction(a))
        )
      )
    )

  protected def sendAndExpectFailure(commandId: Long, description: String): UserFunctionReply = {
    sendFailure(commandId, description)
    val reply = expectMsgType[UserFunctionReply]
    inside(reply.clientAction) {
      case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
        failure should ===(Failure(0, description))
    }
    reply
  }

  protected def sendFailure(commandId: Long, description: String) =
    entity ! CrudStreamOut(
      CrudStreamOut.Message.Failure(
        Failure(
          commandId = commandId,
          description = description
        )
      )
    )

  protected def createAndExpectInitState(initState: Option[CrudInitState]): Unit = {
    userFunction = TestProbe()
    entity = system.actorOf(
      CrudEntity.props(
        CrudEntity.Configuration(ServiceName, UserFunctionName, 30.seconds, 100),
        entityId,
        userFunction.ref,
        concurrencyEnforcer,
        statsCollector.ref,
        null
      ),
      s"crud-test-entity-$entityId"
    )

    val init = userFunction.expectMsgType[CrudStreamIn]
    inside(init.message) {
      case CrudStreamIn.Message.Init(CrudInit(serviceName, eid, state, _)) =>
        serviceName should ===(ServiceName)
        eid should ===(entityId)
        state should ===(initState)
    }
  }

  protected def reactiveAndExpectInitState(initState: Option[CrudInitState]): Unit = {
    cleanUpEntity()

    userFunction = TestProbe()
    reactivatedEntity = system.actorOf(
      CrudEntity.props(
        CrudEntity.Configuration(ServiceName, UserFunctionName, 30.seconds, 100),
        entityId,
        userFunction.ref,
        concurrencyEnforcer,
        statsCollector.ref,
        null
      ),
      s"crud-test-entity-reactivated-$entityId"
    )

    val init = userFunction.expectMsgType[CrudStreamIn]
    inside(init.message) {
      case CrudStreamIn.Message.Init(CrudInit(serviceName, eid, state, _)) =>
        serviceName should ===(ServiceName)
        eid should ===(entityId)
        state should ===(initState)
    }
  }

  private def cleanUpEntity(): Unit = {
    userFunction.testActor ! PoisonPill
    userFunction = null
    entity ! PoisonPill
    entity = null
  }

  before {
    idSeq += 1
  }

  after {
    if (entity != null) {
      cleanUpEntity()
    }

    if (reactivatedEntity != null) {
      userFunction.testActor ! PoisonPill
      userFunction = null
      reactivatedEntity ! PoisonPill
      reactivatedEntity = null
    }
  }

  override protected def beforeAll(): Unit = {
    statsCollector = TestProbe()
    concurrencyEnforcer = system.actorOf(
      ConcurrencyEnforcer.props(ConcurrencyEnforcerSettings(1, 10.second, 5.second), statsCollector.ref),
      "concurrency-enforcer"
    )
  }

  override protected def afterAll(): Unit = {
    statsCollector.testActor ! PoisonPill
    statsCollector = null
    concurrencyEnforcer ! PoisonPill
    concurrencyEnforcer = null

    Await.ready(system.terminate(), 10.seconds)
    shutdown()
  }

}
