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

package io.cloudstate.javasupport.impl.crud

import io.cloudstate.javasupport.impl.crud.CrudImpl.EntityException
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud.{CrudAction, CrudDelete, CrudUpdate}

private[impl] object CrudActionInvocationChecker {
  private val updateActionName = "updateState"
  private val deleteActionName = "deleteState"

  case class CrudActionInvocationContext(entityId: String, commandId: Long, commandName: String)
}

private[impl] trait CrudActionInvocationChecker {
  import CrudActionInvocationChecker._

  def action: Option[CrudAction]
  def stateDeleted: Boolean

  /**
   * It should not be possible to call getState when deleteState have been called.
   * <ul>
   * <li>
   * <code>
   * ctx.deleteState();
   * ctx.getState();
   * </code>
   * </li>
   * </ul>
   */
  def checkStateDeleted(ctx: CrudActionInvocationContext): Unit =
    if (stateDeleted) {
      throw new IllegalStateException(
        s"The CRUD entity [${ctx.entityId}] does not exist and have been deleted with deleteState for command [${ctx.commandName}]"
      )
    }

  /**
   * It should not be possible to call some combinations of getState, updateState and deleteState because we don't
   * what the intention of the caller is.
   * Some examples of invocations that will failed:
   * <ul>
   * <li>
   * <code>
   * ctx.updateState(…);
   * ctx.updateState(…);
   * </code>
   * </li>
   * <li>
   * <code>
   * ctx.deleteState();
   * ctx.updateState(…);
   * </code>
   * </li>
   * <li>
   * <code>
   * ctx.updateState(…);
   * ctx.deleteState();
   * </code>
   * <li>
   * <code>
   * ctx.deleteState();
   * ctx.deleteState();
   * </code>
   * </li>
   * </ul>
   */
  def checkInvocation(ctx: CrudActionInvocationContext, _action: CrudAction): Unit =
    action.map { a =>
      a.action match {
        case Update(_) =>
          throw new EntityException(ctx.entityId,
                                    ctx.commandId,
                                    ctx.commandName,
                                    invocationFailureMessage(ctx, updateActionName, _action))

        case Delete(_) =>
          throw new EntityException(ctx.entityId,
                                    ctx.commandId,
                                    ctx.commandName,
                                    invocationFailureMessage(ctx, deleteActionName, _action))

        case _ =>
      }
    }

  private def invocationFailureMessage(ctx: CrudActionInvocationContext,
                                       firstActionName: String,
                                       _action: CrudAction): String = {
    val name = actionName(_action)
    //s"CRUD entity [$entityId] command [$commandName] cannot run multiples actions ['$firstActionName', '$name']. Please choose only one action between ['updateState', 'deleteState'] to run"
    s"""|CRUD entity [${ctx.entityId}] command [${ctx.commandName}] cannot run multiples actions ['$firstActionName', '$name'].
        | Please choose only one action between ['${updateActionName}', '${deleteActionName}'] to run""".stripMargin
      .replaceAll("\n", " ")
  }

  private def actionName(crudAction: CrudAction): String =
    crudAction.action match {
      case Update(CrudUpdate(_, _)) => updateActionName
      case Delete(CrudDelete(_)) => deleteActionName
      case _ => ""
    }
}
