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

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.annotation.tailrec
import akka.grpc.internal.{
  CancellationBarrierGraphStage,
  Codecs,
  GrpcProtocolNative,
  GrpcResponseHelpers,
  ServerReflectionImpl
}
import akka.NotUsed
import akka.grpc.{ProtobufSerializer, Trailers}
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.headers.RawHeader
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.google.protobuf.{ByteString => ProtobufByteString}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{FileDescriptor, MethodDescriptor}
import grpc.reflection.v1alpha.reflection.ServerReflectionHandler
import io.cloudstate.protocol.entity._
import io.cloudstate.proxy.eventing.Emitter
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.UserFunctionReply
import io.cloudstate.proxy.protobuf.Types
import io.grpc.{Status, StatusRuntimeException}

object Serve {
  private[this] final val fallback: Any => Any = _ => fallback

  private final def checkFallback[B] = fallback.asInstanceOf[Any => B]

  private final val Http404Response: Future[HttpResponse] =
    Future.successful(HttpResponse(StatusCodes.NotFound))

  val mapRequestFailureExceptions: ActorSystem => PartialFunction[Throwable, Trailers] = { _ =>
    {
      case CommandException(msg) => Trailers(Status.UNKNOWN.augmentDescription(msg))
      case e: StatusRuntimeException => Trailers(e.getStatus)
    }
  }

  private[proxy] final object ReplySerializer extends ProtobufSerializer[ProtobufAny] {
    override final def serialize(reply: ProtobufAny): ByteString =
      if (reply.value.isEmpty) ByteString.empty
      else ByteString.fromArrayUnsafe(reply.value.toByteArray)

    override final def deserialize(bytes: ByteString): ProtobufAny =
      throw new UnsupportedOperationException("operation not supported")
  }

  final class CommandHandler(final val entity: ServableEntity,
                             final val method: MethodDescriptor,
                             final val router: UserFunctionRouter,
                             final val emitter: Option[Emitter],
                             final val entityDiscoveryClient: EntityDiscoveryClient,
                             final val log: LoggingAdapter)(implicit ec: ExecutionContext) {

    final val fullCommandName: String = entity.serviceName + "." + method.getName
    final val unary: Boolean = !method.toProto.getClientStreaming && !method.toProto.getServerStreaming
    final val expectedReplyTypeUrl: String = Types.AnyTypeUrlHostName + method.getOutputType.getFullName
    final val commandTypeUrl = Types.AnyTypeUrlHostName + method.getInputType.getFullName

    final def deserialize(bytes: ByteString): UserFunctionRouter.Message = {
      val payload = ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))
      UserFunctionRouter.Message(payload, Metadata.defaultInstance)
    }

    def handle(metadata: Metadata): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed] =
      router.handle(entity.serviceName, method.getName, metadata)

    final val processReplies: Flow[UserFunctionReply, ProtobufAny, NotUsed] = {
      val handler = Flow[UserFunctionReply]
        .map({
          case UserFunctionReply(Some(ClientAction(ClientAction.Action.Reply(reply @ Reply(Some(payload), _, _)), _)),
                                 _,
                                 _) =>
            if (payload.typeUrl != expectedReplyTypeUrl) {
              val msg =
                s"$fullCommandName: Expected reply type_url to be [$expectedReplyTypeUrl] but was [${payload.typeUrl}]."
              log.warning(msg)
              entityDiscoveryClient.reportError(UserFunctionError(s"Warning: $msg"))
            }
            Some(reply)
          case UserFunctionReply(Some(ClientAction(ClientAction.Action.Forward(_), _)), _, _) =>
            log.error("Cannot serialize forward reply, this should have been handled by the UserFunctionRouter")
            None
          case UserFunctionReply(Some(ClientAction(ClientAction.Action.Failure(Failure(_, message, _, _)), _)), _, _) =>
            log.error("User Function responded with a failure: {}", message)
            throw CommandException(message)
          case _ =>
            None
        })
        .collect(Function.unlift(identity))

      emitter match {
        /*
        case Some(e) =>
          handler.mapAsync(4) {
            case Reply(Some(payload), metadata, _) =>
              e.emit(payload, method, metadata).map(_ => payload)
          }
         */
        case _ => handler.map(_.payload.get)
      }

    }
  }

  private def createResponse(request: HttpRequest, headers: List[HttpHeader], protobufs: Source[ProtobufAny, NotUsed])(
      implicit system: ActorSystem
  ): HttpResponse = {
    val responseWriter = GrpcProtocolNative.newWriter(Codecs.negotiate(request))
    val response =
      GrpcResponseHelpers(protobufs, Serve.mapRequestFailureExceptions)(Serve.ReplySerializer, responseWriter, system)
    response.withHeaders(response.headers ++ headers)
  }

  def createRoute(entities: Seq[ServableEntity],
                  router: UserFunctionRouter,
                  entityDiscoveryClient: EntityDiscoveryClient,
                  fileDescriptors: Seq[FileDescriptor],
                  emitters: Map[String, Emitter])(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val log = Logging(sys.eventStream, Serve.getClass)
    val grpcProxy = createGrpcApi(entities, router, entityDiscoveryClient, emitters)
    val grpcHandler = Function.unlift { request: HttpRequest =>
      val asResponse = grpcProxy.andThen { futureResult =>
        Some(futureResult.map {
          case (headers, messages) =>
            createResponse(request, headers, messages)
        })
      }
      asResponse.applyOrElse(request, (_: HttpRequest) => None)
    }
    val routes = Array(
      GrpcWebSupport.wrapGrpcHandler(grpcHandler),
      HttpApi.serve(entities.map(_.serviceDescriptor -> grpcProxy).toList),
      handleNetworkProbe(),
      ServerReflectionHandler.partial(
        ServerReflectionImpl(fileDescriptors, entities.map(_.serviceName).sorted.toList)
      )
    )

    { // Creates a fast implementation of multi-PartialFunction composition
      case req =>
        @tailrec def matchRoutes(req: HttpRequest, idx: Int): Future[HttpResponse] =
          if (idx < routes.length) {
            val res = routes(idx).applyOrElse(req, checkFallback): AnyRef
            if (res eq fallback) matchRoutes(req, idx + 1)
            else res.asInstanceOf[Future[HttpResponse]]
          } else {
            log.debug("Not found request: " + req.getUri())
            Http404Response
          }

        matchRoutes(req, 0)
    }
  }

  /**
   * Knative network probe handler.
   */
  def handleNetworkProbe(): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    case req if req.headers.exists(_.name.equalsIgnoreCase("K-Network-Probe")) =>
      Future.successful(
        req.headers.find(_.name.equalsIgnoreCase("K-Network-Probe")).get match {
          case header if header.value == "queue" => HttpResponse(entity = HttpEntity("queue"))
          case other =>
            HttpResponse(status = StatusCodes.BadRequest,
                         entity = HttpEntity(s"unexpected probe header value: ${other.value}"))
        }
      )
  }

  private[this] final def createGrpcApi(entities: Seq[ServableEntity],
                                        router: UserFunctionRouter,
                                        entityDiscoveryClient: EntityDiscoveryClient,
                                        emitters: Map[String, Emitter])(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): PartialFunction[HttpRequest, Future[(List[HttpHeader], Source[ProtobufAny, NotUsed])]] = {
    val log = Logging(sys.eventStream, Serve.getClass)
    val rpcMethodSerializers = (for {
      entity <- entities.iterator
      method <- entity.serviceDescriptor.getMethods.iterator.asScala
    } yield {
      val handler =
        new CommandHandler(entity, method, router, emitters.get(method.getFullName), entityDiscoveryClient, log)
      (Path / entity.serviceName / method.getName, handler)
    }).toMap

    val routes: PartialFunction[HttpRequest, Future[(List[HttpHeader], Source[ProtobufAny, NotUsed])]] = {
      case req: HttpRequest if rpcMethodSerializers.contains(req.uri.path) =>
        log.debug("Received gRPC request [{}]", req.uri.path)

        val handler = rpcMethodSerializers(req.uri.path)

        val metadata = Metadata(req.headers.map { header =>
          MetadataEntry(header.name(), MetadataEntry.Value.StringValue(header.value))
        })

        val reader = GrpcProtocolNative.newReader(Codecs.detect(req).get)

        req.entity.dataBytes
          .viaMat(reader.dataFrameDecoder)(Keep.none)
          .map(handler.deserialize)
          .via(new CancellationBarrierGraphStage) // In gRPC we signal failure by returning an error code, so we don't want the cancellation bubbled out
          .via(handler.handle(metadata))
          .prefixAndTail(1)
          .runWith(Sink.head)
          .map {
            case (maybeFirstReply, rest) =>
              val (metadata, replies) = maybeFirstReply match {
                case Seq(
                    reply @ UserFunctionReply(Some(ClientAction(ClientAction.Action.Reply(Reply(_, metadata, _)), _)),
                                              _,
                                              _)
                    ) =>
                  (metadata.getOrElse(Metadata.defaultInstance), Source.single(reply).concat(rest))
                case other =>
                  (Metadata.defaultInstance, Source(other).concat(rest))
              }

              val headers = metadata.entries.iterator.collect {
                case MetadataEntry(key, MetadataEntry.Value.StringValue(value), _) => RawHeader(key, value)
              }.toList

              (headers, replies.via(handler.processReplies))
          }
    }

    routes
  }

  private case class CommandException(msg: String) extends RuntimeException(msg, null, false, false)

}
