package io.cloudstate.javasupport.impl

import java.util.Optional

import io.cloudstate.javasupport.{ClientActionContext, Context, EffectContext}

import scala.util.control.NoStackTrace
import com.google.protobuf.{Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.protocol.entity.{ClientAction, Failure, Reply}

private[impl] trait ActivatableContext extends Context {
  private final var active = true
  final def deactivate(): Unit = active = false
  final def checkActive(): Unit = if (!active) throw new IllegalStateException("Context no longer active!")
}

private[impl] trait AbstractEffectContext extends EffectContext {
  self: ActivatableContext =>

  override final def effect(): Unit = ???
}

private[impl] trait AbstractClientActionContext extends ClientActionContext {
  self: ActivatableContext =>

  def commandId: Long

  private final var error: Option[String] = None

  override final def fail(errorMessage: String): Unit = {
    checkActive()
    if (error.isEmpty) {
      error = Some(errorMessage)
      throw FailInvoked
    } else throw new IllegalStateException("fail(…) already previously invoked!")
  }

  override final def forward(): Unit = ??? // todo!!

  final def hasError: Boolean = error.isDefined

  final def createClientAction(reply: Optional[JavaPbAny], allowNoReply: Boolean): Option[ClientAction] = {
    error match {
      case Some(msg) => Some(ClientAction(ClientAction.Action.Failure(Failure(commandId, msg))))
      case None =>
        if (reply.isPresent) {
          Some(ClientAction(ClientAction.Action.Reply(Reply(Some(ScalaPbAny.fromJavaProto(reply.get()))))))
        } else if (allowNoReply) {
          // todo handle forwarding!
          None
        } else {
          throw new RuntimeException("No reply or forward returned by command handler!")
        }
    }
  }
}

object FailInvoked extends Throwable with NoStackTrace { override def toString: String = "CommandContext.fail(…) invoked" }

