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

package io.cloudstate.javasupport.impl.action

import java.util

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.action._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, Metadata, Service, ServiceCall, ServiceCallFactory}
import io.cloudstate.protocol.action.{ActionCommand, ActionProtocol, ActionResponse}
import io.cloudstate.protocol.entity.{Failure, Forward, Reply, SideEffect, Metadata => PbMetadata}

import scala.concurrent.Future
import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._

final class ActionService(val actionHandler: ActionHandler,
                          override val descriptor: Descriptors.ServiceDescriptor,
                          val anySupport: AnySupport)
    extends Service {

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    actionHandler match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  override final val entityType = ActionProtocol.name
}

final class ActionProtocolImpl(_system: ActorSystem, services: Map[String, ActionService], rootContext: Context)
    extends ActionProtocol {

  import _system.dispatcher
  implicit val system: ActorSystem = _system

  private def toJavaPbAny(any: Option[ScalaPbAny]) =
    any.fold(JavaPbAny.getDefaultInstance)(ScalaPbAny.toJavaProto)

  private def toOptionPbMetadata(metadata: Metadata) =
    metadata match {
      case impl: MetadataImpl if impl.entries.nonEmpty =>
        Some(PbMetadata(impl.entries))
      case _: MetadataImpl => None
      case other => throw new RuntimeException(s"Unknown metadata implementation: ${other.getClass}, cannot send")
    }

  private def actionMessageToReply(msg: ActionReply[JavaPbAny]) = {
    val response = msg match {
      case message: MessageReply[JavaPbAny] =>
        ActionResponse.Response.Reply(
          Reply(
            Some(ScalaPbAny.fromJavaProto(message.payload())),
            toOptionPbMetadata(message.metadata())
          )
        )
      case forward: ForwardReply[JavaPbAny] =>
        ActionResponse.Response.Forward(
          Forward(
            forward.serviceCall().ref().method().getService.getFullName,
            forward.serviceCall().ref().method().getName,
            Some(ScalaPbAny.fromJavaProto(forward.serviceCall().message())),
            toOptionPbMetadata(forward.serviceCall().metadata())
          )
        )
      // ie, NoReply
      case _ => ActionResponse.Response.Empty
    }

    val effects = msg match {
      case impl: ActionReplyImpl[_] =>
        impl._effects
      case other =>
        other.effects().asScala.toList
    }
    val encodedEffects = effects.map { effect =>
      SideEffect(
        effect.serviceCall().ref().method().getService.getFullName,
        effect.serviceCall().ref().method().getName,
        Some(ScalaPbAny.fromJavaProto(effect.serviceCall().message())),
        effect.synchronous(),
        toOptionPbMetadata(effect.serviceCall().metadata())
      )
    }

    ActionResponse(response, encodedEffects)
  }

  /**
   * Handle a unary command.
   * The input command will contain the service name, command name, request metadata and the command
   * payload. The reply may contain a direct reply, a forward or a failure, and it may contain many
   * side effects.
   */
  override def handleUnary(in: ActionCommand): Future[ActionResponse] =
    services.get(in.serviceName) match {
      case Some(service) =>
        val context = createContext(in)
        service.actionHandler
          .handleUnary(in.name, MessageEnvelope.of(toJavaPbAny(in.payload), context.metadata()), context)
          .toScala
          .map(actionMessageToReply)
      case None =>
        Future.successful(
          ActionResponse(ActionResponse.Response.Failure(Failure(0, "Unknown service: " + in.serviceName)))
        )
    }

  /**
   * Handle a streamed in command.
   * The first message in will contain the request metadata, including the service name and command
   * name. It will not have an associated payload set. This will be followed by zero to many messages
   * in with a payload, but no service name or command name set.
   * The semantics of stream closure in this protocol map 1:1 with the semantics of gRPC stream closure,
   * that is, when the client closes the stream, the stream is considered half closed, and the server
   * should eventually, but not necessarily immediately, send a response message with a status code and
   * trailers.
   * If however the server sends a response message before the client closes the stream, the stream is
   * completely closed, and the client should handle this and stop sending more messages.
   * Either the client or the server may cancel the stream at any time, cancellation is indicated
   * through an HTTP2 stream RST message.
   */
  override def handleStreamedIn(in: Source[ActionCommand, NotUsed]): Future[ActionResponse] =
    in.prefixAndTail(1)
      .runWith(Sink.head)
      .flatMap {
        case (Nil, _) =>
          Future.successful(
            ActionResponse(
              ActionResponse.Response.Failure(
                Failure(
                  0,
                  "Cloudstate protocol failure: expected command message with service name and command name, but got empty stream"
                )
              )
            )
          )
        case (Seq(call), messages) =>
          services.get(call.serviceName) match {
            case Some(service) =>
              service.actionHandler
                .handleStreamedIn(
                  call.name,
                  messages.map { message =>
                    val metadata = new MetadataImpl(message.metadata.map(_.entries.toVector).getOrElse(Nil))
                    MessageEnvelope.of(toJavaPbAny(message.payload), metadata)
                  }.asJava,
                  createContext(call)
                )
                .toScala
                .map(actionMessageToReply)
            case None =>
              Future.successful(
                ActionResponse(ActionResponse.Response.Failure(Failure(0, "Unknown service: " + call.serviceName)))
              )
          }
      }

  /**
   * Handle a streamed out command.
   * The input command will contain the service name, command name, request metadata and the command
   * payload. Zero or more replies may be sent, each containing either a direct reply, a forward or a
   * failure, and each may contain many side effects. The stream to the client will be closed when the
   * this stream is closed, with the same status as this stream is closed with.
   * Either the client or the server may cancel the stream at any time, cancellation is indicated
   * through an HTTP2 stream RST message.
   */
  override def handleStreamedOut(in: ActionCommand): Source[ActionResponse, NotUsed] =
    services.get(in.serviceName) match {
      case Some(service) =>
        val context = createContext(in)
        service.actionHandler
          .handleStreamedOut(in.name, MessageEnvelope.of(toJavaPbAny(in.payload), context.metadata()), context)
          .asScala
          .map(actionMessageToReply)
      case None =>
        Source.single(ActionResponse(ActionResponse.Response.Failure(Failure(0, "Unknown service: " + in.serviceName))))
    }

  /**
   * Handle a full duplex streamed command.
   * The first message in will contain the request metadata, including the service name and command
   * name. It will not have an associated payload set. This will be followed by zero to many messages
   * in with a payload, but no service name or command name set.
   * Zero or more replies may be sent, each containing either a direct reply, a forward or a failure,
   * and each may contain many side effects.
   * The semantics of stream closure in this protocol map 1:1 with the semantics of gRPC stream closure,
   * that is, when the client closes the stream, the stream is considered half closed, and the server
   * should eventually, but not necessarily immediately, close the streamage with a status code and
   * trailers.
   * If however the server closes the stream with a status code and trailers, the stream is immediately
   * considered completely closed, and no further messages sent by the client will be handled by the
   * server.
   * Either the client or the server may cancel the stream at any time, cancellation is indicated
   * through an HTTP2 stream RST message.
   */
  override def handleStreamed(in: Source[ActionCommand, NotUsed]): Source[ActionResponse, NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Nil, _) =>
          Source.single(
            ActionResponse(
              ActionResponse.Response.Failure(
                Failure(
                  0,
                  "Cloudstate protocol failure: expected command message with service name and command name, but got empty stream"
                )
              )
            )
          )
        case (Seq(call), messages) =>
          services.get(call.serviceName) match {
            case Some(service) =>
              service.actionHandler
                .handleStreamed(
                  call.name,
                  messages.map { message =>
                    val metadata = new MetadataImpl(message.metadata.map(_.entries.toVector).getOrElse(Nil))
                    MessageEnvelope.of(toJavaPbAny(message.payload), metadata)
                  }.asJava,
                  createContext(call)
                )
                .asScala
                .map(actionMessageToReply)
            case None =>
              Source.single(
                ActionResponse(ActionResponse.Response.Failure(Failure(0, "Unknown service: " + call.serviceName)))
              )
          }
      }

  private def createContext(in: ActionCommand): ActionContext = {
    val metadata = new MetadataImpl(in.metadata.map(_.entries.toVector).getOrElse(Nil))
    new ActionContextImpl(metadata)
  }

  class ActionContextImpl(override val metadata: Metadata) extends ActionContext {
    override val serviceCallFactory: ServiceCallFactory = rootContext.serviceCallFactory()
  }
}

trait ActionReplyImpl[T] extends ActionReply[T] {
  def _effects: List[Effect]
  override def effects(): util.Collection[Effect] = _effects.asJava
}
case class MessageEnvelopeImpl[T](payload: T, metadata: Metadata) extends MessageEnvelope[T]
case class MessageReplyImpl[T](payload: T, metadata: Metadata, _effects: List[Effect])
    extends MessageReply[T]
    with ActionReplyImpl[T] {
  def this(payload: T, metadata: Metadata) = this(payload, metadata, Nil)
  override def withEffects(effect: Effect*): MessageReply[T] = MessageReplyImpl(payload, metadata, _effects ++ effect)
}
case class ForwardReplyImpl[T](serviceCall: ServiceCall, _effects: List[Effect])
    extends ForwardReply[T]
    with ActionReplyImpl[T] {
  def this(serviceCall: ServiceCall) = this(serviceCall, Nil)
  override def withEffects(effect: Effect*): ForwardReply[T] = ForwardReplyImpl(serviceCall, _effects ++ effect)
}
case class NoReply[T](_effects: List[Effect]) extends ActionReplyImpl[T] {
  override def withEffects(effect: Effect*): ActionReply[T] = NoReply(_effects ++ effect)
}
object NoReply {
  private val instance = NoReply[Any](Nil)
  def apply[T]: ActionReply[T] = instance.asInstanceOf[NoReply[T]]
}
case class EffectImpl(serviceCall: ServiceCall, synchronous: Boolean) extends Effect
