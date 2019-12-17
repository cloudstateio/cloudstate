package io.cloudstate.javasupport.impl

import java.util.Optional

import io.cloudstate.javasupport.{ClientActionContext, Context, EffectContext, ServiceCall}

import scala.util.control.NoStackTrace
import com.google.protobuf.{Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.protocol.entity.{ClientAction, Failure, Forward, Reply, SideEffect}

private[impl] trait ActivatableContext extends Context {
  private final var active = true
  final def deactivate(): Unit = active = false
  final def checkActive(): Unit = if (!active) throw new IllegalStateException("Context no longer active!")
}

private[impl] trait AbstractEffectContext extends EffectContext {
  self: ActivatableContext =>

  private final var effects = List.empty[SideEffect]

  override final def effect(effect: ServiceCall, synchronous: Boolean): Unit = {
    checkActive()
    SideEffect(
      serviceName = effect.ref().method().getService.getFullName,
      commandName = effect.ref().method().getName,
      payload = Some(ScalaPbAny.fromJavaProto(effect.message())),
      synchronous = synchronous
    ) :: effects
  }

  final def sideEffects: List[SideEffect] = effects.reverse
}

private[impl] trait AbstractClientActionContext extends ClientActionContext {
  self: ActivatableContext =>

  def commandId: Long

  private final var error: Option[String] = None
  private final var forward: Option[Forward] = None

  override final def fail(errorMessage: String): RuntimeException = {
    checkActive()
    if (error.isEmpty) {
      error = Some(errorMessage)
      throw FailInvoked
    } else throw new IllegalStateException("fail(…) already previously invoked!")
  }

  override final def forward(to: ServiceCall): Unit = {
    checkActive()
    if (forward.isDefined) {
      throw new IllegalStateException("This context has already forwarded.")
    }
    forward = Some(
      Forward(
        serviceName = to.ref().method().getService.getFullName,
        commandName = to.ref().method().getName,
        payload = Some(ScalaPbAny.fromJavaProto(to.message()))
      )
    )
  }

  final def hasError: Boolean = error.isDefined

  final def createClientAction(reply: Optional[JavaPbAny], allowNoReply: Boolean): Option[ClientAction] =
    error match {
      case Some(msg) => Some(ClientAction(ClientAction.Action.Failure(Failure(commandId, msg))))
      case None =>
        if (reply.isPresent) {
          if (forward.isDefined) {
            throw new IllegalStateException(
              "Both a reply was returned, and a forward message was sent, choose one or the other."
            )
          }
          Some(ClientAction(ClientAction.Action.Reply(Reply(Some(ScalaPbAny.fromJavaProto(reply.get()))))))
        } else if (forward.isDefined) {
          Some(ClientAction(ClientAction.Action.Forward(forward.get)))
        } else if (allowNoReply) {
          None
        } else {
          throw new RuntimeException("No reply or forward returned by command handler!")
        }
    }
}

object FailInvoked extends Throwable with NoStackTrace {
  override def toString: String = "CommandContext.fail(…) invoked"
}
