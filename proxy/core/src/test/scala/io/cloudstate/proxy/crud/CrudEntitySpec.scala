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

import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.entity.{ClientAction, Failure}
import io.cloudstate.proxy.crud.CrudEntity.{Stop, StreamClosed, StreamFailed}
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import org.scalatest.Ignore

import scala.concurrent.duration._

@Ignore
class CrudEntitySpec extends AbstractCrudEntitySpec {

  import AbstractCrudEntitySpec._

  "The CrudEntity" should {

    "be initialised successfully" in {
      createAndExpectInitState(Some(CrudInitState()))
      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "handle update commands and reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      watch(entity)

      val commandId1 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(commandId1, Some(Update(CrudUpdate(Some(state1)))))

      val commandId2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(commandId2, Some(Update(CrudUpdate(Some(state2)))))

      // passivating the entity
      entity ! Stop
      expectTerminated(entity)

      // reactivating the entity
      reactiveAndExpectInitState(Some(CrudInitState(Some(state2))))

      val commandId3 = sendAndExpectCommand("cmd", command, reactivatedEntity)
      sendAndExpectReply(commandId3, Some(Update(CrudUpdate(Some(state2)))), reactivatedEntity)

      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "handle delete command and reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      watch(entity)

      val commandId1 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(commandId1, Some(Update(CrudUpdate(Some(state1)))))

      val commandId2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(commandId2, Some(Delete(CrudDelete())))

      // passivating the entity
      entity ! Stop
      expectTerminated(entity)

      // reactivating the entity
      reactiveAndExpectInitState(Some(CrudInitState(None)))

      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "handle failure reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectFailure(cid, "description")
      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "stash commands when another command is being executed" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendAndExpectCommand("cmd", command)
      entity ! EntityCommand(entityId, "cmd", Some(command))
      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "crash when there is no command being executed for the reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendReply(-1, None)
      // expect crash and restart
      userFunction.expectMsgType[CrudStreamIn]
      userFunction.expectNoMessage(200.millis)
      expectNoMessage(200.millis)
    }

    "crash when the command being executed does not have the same command id as the one of the reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendAndExpectCommand("cmd", command)
      // send reply with wrong command id
      sendReply(-1, None)

      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Incorrect command id in reply, expecting 1 but got -1"))
      }
      expectNoMessage(200.millis)
    }

    "crash on failure reply with command id 0" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendFailure(0, "description")
      expectNoMessage(200.millis)
    }

    "crash on failure reply when there is no command being executed" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendFailure(1, "description")
      expectNoMessage(200.millis)
    }

    "crash on failure reply when the command being executed does not have the same command id as the one of the reply" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendAndExpectCommand("cmd", command)
      //entity ! EntityCommand(entityId, "cmd", Some(command))
      sendFailure(-1, "description")
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Incorrect command id in failure, expecting 1 but got -1"))
      }
      expectNoMessage(200.millis)
    }

    "crash when output message is empty and no command is being executed" in {
      createAndExpectInitState(Some(CrudInitState()))
      entity ! CrudStreamOut(CrudStreamOut.Message.Empty)
      expectNoMessage(200.millis)
    }

    "crash when stream out message is empty and a command is being executed" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendAndExpectCommand("cmd", command)
      entity ! CrudStreamOut(CrudStreamOut.Message.Empty)
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Empty or unknown message from entity output stream"))
      }
      expectNoMessage(200.millis)
    }

    "stop when received StreamClosed message" in {
      createAndExpectInitState(Some(CrudInitState()))
      watch(entity)

      sendAndExpectCommand("cmd", command)
      entity ! StreamClosed
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Unexpected CRUD entity termination"))
      }
      expectTerminated(entity)
    }

    "handle StreamFailed message" in {
      createAndExpectInitState(Some(CrudInitState()))
      sendAndExpectCommand("cmd", command)
      entity ! StreamFailed(new RuntimeException("test stream failed"))
      inside(expectMsgType[UserFunctionReply].clientAction) {
        case Some(ClientAction(ClientAction.Action.Failure(failure), _)) =>
          failure should ===(Failure(0, "Unexpected CRUD entity termination"))
      }
    }
  }
}
