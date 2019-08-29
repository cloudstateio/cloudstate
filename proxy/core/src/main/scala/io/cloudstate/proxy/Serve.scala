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

package io.cloudstate.proxy

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import GrpcMarshalling.{marshalStream, unmarshalStream}
import akka.NotUsed
import akka.grpc.{Codecs, ProtobufSerializer}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.actor.{ActorRef, ActorSystem}
import akka.util.ByteString
import akka.stream.Materializer
import com.google.protobuf.{ByteString => ProtobufByteString}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{Descriptor, FileDescriptor}
import io.cloudstate.protocol.entity._
import akka.stream.scaladsl.{Flow, Source}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}
import io.grpc.Status
import org.slf4j.LoggerFactory


object Serve {

  private final val log = LoggerFactory.getLogger(getClass)
  // When the entity key is made up of multiple fields, this is used to separate them
  final val AnyTypeUrlHostName = "type.googleapis.com/"

  private final val NotFound: PartialFunction[HttpRequest, Future[HttpResponse]] = {
    case req: HttpRequest =>
      log.debug("Not found request: " + req.getUri())
      Future.successful(HttpResponse(StatusCodes.NotFound))
  }

  private final object ReplySerializer extends ProtobufSerializer[ProtobufByteString] {
    override final def serialize(reply: ProtobufByteString): ByteString =
      if (reply.isEmpty) {
        ByteString.empty
      } else {
        ByteString.fromArrayUnsafe(reply.toByteArray)
      }

    override final def deserialize(bytes: ByteString): ProtobufByteString =
      if (bytes.isEmpty) ProtobufByteString.EMPTY
      else ProtobufByteString.readFrom(bytes.iterator.asInputStream)
  }

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[UserFunctionCommand] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName

    // Should not be used in practice
    override final def serialize(command: UserFunctionCommand): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): UserFunctionCommand = {
      UserFunctionCommand(
        name = commandName,
        payload = Some(ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer)))
      )
    }
  }

  private final case class CommandHandler(fullCommandName: String, serializer: CommandSerializer, flow: Flow[UserFunctionCommand,
    UserFunctionReply, NotUsed], unary: Boolean, expectedReplyTypeUrl: String)

  def createRoute(entities: Seq[ServableEntity], router: UserFunctionRouter, statsCollector: ActorRef,
    entityDiscoveryClient: EntityDiscoveryClient, fileDescriptors: Seq[FileDescriptor])(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {

    compileProxy(entities, router, statsCollector, entityDiscoveryClient) orElse // Fast path
                       handleNetworkProbe() orElse
                       Reflection.serve(fileDescriptors, entities.map(_.serviceName).toList) orElse
                       HttpApi.serve(router, entities, entityDiscoveryClient) orElse // Slow path
                       NotFound // No match. TODO: Consider having the caller of this method deal with this condition
  }

  /**
    * Knative network probe handler.
    */
  def handleNetworkProbe(): PartialFunction[HttpRequest, Future[HttpResponse]] = Function.unlift { req =>
    req.headers.find(_.name.equalsIgnoreCase("K-Network-Probe")).map { header =>
      Future.successful(header.value match {
        case "queue" => HttpResponse(entity = HttpEntity("queue"))
        case other => HttpResponse(status = StatusCodes.BadRequest, entity = HttpEntity(s"unexpected probe header value: $other"))
      })
    }
  }

  private[this] final def compileProxy(entities: Seq[ServableEntity], router: UserFunctionRouter, statsCollector: ActorRef, entityDiscoveryClient: EntityDiscoveryClient)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {

    val rpcMethodSerializers = (for {
      entity <- entities.iterator
      method <- entity.serviceDescriptor.getMethods.iterator.asScala
    } yield {
      (Path / entity.serviceName / method.getName, CommandHandler(
        fullCommandName = entity.serviceName + "." + method.getName,
        new CommandSerializer(method.getName, method.getInputType),
        router.handle(entity.serviceName),
        unary = !method.toProto.getClientStreaming && !method.toProto.getServerStreaming,
        expectedReplyTypeUrl = AnyTypeUrlHostName + method.getOutputType.getFullName
      ))
    }).toMap

    val mapRequestFailureExceptions: ActorSystem => PartialFunction[Throwable, Status] = {
      val pf: PartialFunction[Throwable, Status] = {
        case CommandException(msg) => Status.UNKNOWN.augmentDescription(msg)
      }
      _ => pf
    }

    {
      case req: HttpRequest if rpcMethodSerializers.contains(req.uri.path) =>
        val startTime = System.nanoTime()
        val responseCodec = Codecs.negotiate(req)
        val handler = rpcMethodSerializers(req.uri.path)
        if (handler.unary) {
          // Only report request stats for unary commands, doesn't make sense for streamed
          statsCollector ! StatsCollector.RequestReceived
        }
        unmarshalStream(req)(handler.serializer, mat)
          .map { commands =>
            val pipeline: Source[ProtobufByteString, NotUsed] = commands
              .via(handler.flow)
              .map { reply =>
                reply.clientAction match {
                  case Some(ClientAction(ClientAction.Action.Reply(Reply(Some(payload))))) =>
                    if (payload.typeUrl != handler.expectedReplyTypeUrl) {
                      val msg = s"${handler.fullCommandName}: Expected reply type_url to be [${handler.expectedReplyTypeUrl}] but was [${payload.typeUrl}]."
                      log.warn(msg)
                      entityDiscoveryClient.reportError(UserFunctionError("Warning: " + msg))
                    }
                    Some(payload.value)
                  case Some(ClientAction(ClientAction.Action.Forward(_))) =>
                    log.error("Cannot serialize forward reply, this should have been handled by the UserFunctionRouter")
                    None
                  case Some(ClientAction(ClientAction.Action.Failure(Failure(_, message)))) =>
                    throw CommandException(message)
                  case _ =>
                    None
                }
              }.collect(Function.unlift(identity))
              .watchTermination() { (_, complete) =>
                if (handler.unary) {
                  complete.onComplete { _ =>
                    statsCollector ! StatsCollector.ResponseSent(System.nanoTime() - startTime)
                  }
                }
                NotUsed
              }

            marshalStream(pipeline, mapRequestFailureExceptions)(ReplySerializer, mat, responseCodec, sys)
          }.recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }

  private case class CommandException(msg: String) extends RuntimeException(msg, null, false, false)
}

