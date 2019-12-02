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
import scala.concurrent.{ExecutionContext, Future, Promise}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import GrpcMarshalling.{marshalStream, unmarshalStream}
import akka.{Done, NotUsed}
import akka.grpc.{Codecs, ProtobufSerializer}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.actor.{ActorRef, ActorSystem}
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, RunnableGraph, Source}
import com.google.protobuf.{ByteString => ProtobufByteString}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{Descriptor, FileDescriptor, MethodDescriptor}
import io.cloudstate.protocol.entity._
import io.cloudstate.proxy.eventing.{Emitter, Emitters, EventingManager, EventingSupport}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}
import io.grpc.Status
import org.slf4j.{Logger, LoggerFactory}

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

  final class CommandSerializer(val commandName: String, desc: Descriptor)
      extends ProtobufSerializer[UserFunctionCommand] {
    final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName

    // Should not be used in practice
    override final def serialize(command: UserFunctionCommand): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): UserFunctionCommand =
      parse(ProtobufByteString.copyFrom(bytes.asByteBuffer))

    final def parse(bytes: ProtobufByteString): UserFunctionCommand =
      UserFunctionCommand(
        name = commandName,
        payload = Some(ProtobufAny(typeUrl = commandTypeUrl, value = bytes))
      )
  }

  final class CommandHandler(final val entity: ServableEntity,
                             final val method: MethodDescriptor,
                             final val router: UserFunctionRouter) {

    final val fullCommandName: String = entity.serviceName + "." + method.getName
    final val serializer: CommandSerializer = new CommandSerializer(method.getName, method.getInputType)
    final val flow: Flow[UserFunctionCommand, UserFunctionReply, NotUsed] = router.handle(entity.serviceName)
    final val unary: Boolean = !method.toProto.getClientStreaming && !method.toProto.getServerStreaming
    final val expectedReplyTypeUrl: String = AnyTypeUrlHostName + method.getOutputType.getFullName

    def flowUsing(entityDiscoveryClient: EntityDiscoveryClient,
                  log: Logger,
                  futureEmitter: Future[Emitter]): Flow[UserFunctionCommand, ProtobufByteString, NotUsed] =
      flow
        .mapAsync(1)({ command =>
          futureEmitter.map({
            emitter =>
              command.clientAction match {
                case Some(ClientAction(ClientAction.Action.Reply(Reply(Some(payload))))) =>
                  if (payload.typeUrl != expectedReplyTypeUrl) {
                    val msg =
                      s"${fullCommandName}: Expected reply type_url to be [${expectedReplyTypeUrl}] but was [${payload.typeUrl}]."
                    log.warn(msg)
                    entityDiscoveryClient.reportError(UserFunctionError(s"Warning: $msg"))
                  } else {
                    val _ = emitter.emit(payload, method) // TODO: check returned boolean?
                  }
                  Some(payload.value) // FIXME instead throw exception?!
                case Some(ClientAction(ClientAction.Action.Forward(_))) =>
                  log.error("Cannot serialize forward reply, this should have been handled by the UserFunctionRouter")
                  None
                case Some(ClientAction(ClientAction.Action.Failure(Failure(_, message)))) =>
                  log.error("User Function responded with a failure: {}", message)
                  None
                case _ =>
                  None
              }
          })(router.ec)
        })
        .collect(Function.unlift(identity)) // Keep only the valid payloads, possible candidate for ExecutionContext.parasitic in 2.13
  }

  def createRoute(entities: Seq[ServableEntity],
                  router: UserFunctionRouter,
                  statsCollector: ActorRef,
                  entityDiscoveryClient: EntityDiscoveryClient,
                  fileDescriptors: Seq[FileDescriptor],
                  eventingSupport: Option[EventingSupport])(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): (PartialFunction[HttpRequest, Future[HttpResponse]], Option[RunnableGraph[Future[Done]]]) = {
    val (grpcProxy, eventingGraph) =
      createGrpcApi(entities, router, statsCollector, entityDiscoveryClient, eventingSupport)

    val route = grpcProxy orElse // Fast path
      HttpApi.serve(entities.map(_.serviceDescriptor -> grpcProxy).toList) orElse
      handleNetworkProbe() orElse
      Reflection.serve(fileDescriptors, entities.map(_.serviceName).toList) orElse
      NotFound // No match. TODO: Consider having the caller of this method deal with this condition

    (route, eventingGraph)
  }

  /**
   * Knative network probe handler.
   */
  def handleNetworkProbe(): PartialFunction[HttpRequest, Future[HttpResponse]] = Function.unlift { req =>
    req.headers.find(_.name.equalsIgnoreCase("K-Network-Probe")).map { header =>
      Future.successful(header.value match {
        case q @ "queue" => HttpResponse(entity = HttpEntity(q))
        case other =>
          HttpResponse(status = StatusCodes.BadRequest, entity = HttpEntity(s"unexpected probe header value: $other"))
      })
    }
  }

  private[this] final def createGrpcApi(entities: Seq[ServableEntity],
                                        router: UserFunctionRouter,
                                        statsCollector: ActorRef,
                                        entityDiscoveryClient: EntityDiscoveryClient,
                                        eventingSupport: Option[EventingSupport])(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): (PartialFunction[HttpRequest, Future[HttpResponse]], Option[RunnableGraph[Future[Done]]]) = {
    val p = Promise[Emitter]
    val eventingGraph =
      eventingSupport.flatMap(
        support =>
          EventingManager
            .createStreams(router, entityDiscoveryClient, entities, support)
            .map(_.mapMaterializedValue({ case (emitter, done) => p.success(emitter); done }))
      ) match {
        case None =>
          p.success(Emitters.ignore)
          None
        case some =>
          some
      }

    val rpcMethodSerializers = (for {
      entity <- entities.iterator
      method <- entity.serviceDescriptor.getMethods.iterator.asScala
    } yield {
      val handler = new CommandHandler(entity, method, router)
      (Path / entity.serviceName / method.getName, handler)
    }).toMap

    val mapRequestFailureExceptions: ActorSystem => PartialFunction[Throwable, Status] = { _ =>
      {
        case CommandException(msg) => Status.UNKNOWN.augmentDescription(msg)
      }
    }

    val routes: PartialFunction[HttpRequest, Future[HttpResponse]] = {
      case req: HttpRequest if rpcMethodSerializers.contains(req.uri.path) =>
        val startTime = System.nanoTime()
        val responseCodec = Codecs.negotiate(req)
        val handler = rpcMethodSerializers(req.uri.path)

        // Only report request stats for unary commands, doesn't make sense for streamed
        if (handler.unary) {
          statsCollector ! StatsCollector.RequestReceived
        }

        unmarshalStream(req)(handler.serializer, mat) // FIXME Figure out if we need to deal with unmarshal vs unmarshal stream here
          .map({ commands =>
            marshalStream(
              commands.via({
                val pipeline =
                  handler
                    .flowUsing(entityDiscoveryClient, log, p.future)
                if (handler.unary) {
                  pipeline.watchTermination() { (_, complete) =>
                    complete.onComplete { _ =>
                      statsCollector ! StatsCollector.ResponseSent(System.nanoTime() - startTime)
                    }
                    NotUsed
                  }
                } else {
                  pipeline
                }
              }),
              mapRequestFailureExceptions
            )(ReplySerializer, mat, responseCodec, sys)
          })
          .recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }

    (routes, eventingGraph)
  }

  private case class CommandException(msg: String) extends RuntimeException(msg, null, false, false)
}
