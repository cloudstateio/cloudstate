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
import akka.grpc.{Codecs, ProtobufSerializer}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, HttpEntity}
import akka.http.scaladsl.model.Uri.Path
import akka.actor.{ActorRef, ActorSystem}
import akka.util.{ByteString, Timeout}
import akka.pattern.ask
import akka.stream.Materializer
import com.google.api.{AnnotationsProto, HttpProto}
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString}
import com.google.protobuf.empty.{EmptyProto => ProtobufEmptyProto}
import com.google.protobuf.any.{Any => ProtobufAny, AnyProto => ProtobufAnyProto}
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{descriptor => ScalaPBDescriptorProtos}
import io.cloudstate.entitykey._
import io.cloudstate.entity._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import StateManager.CommandFailure
import io.grpc.Status
import io.cloudstate.proxy.statemanager.{GrpcEntityCommand, GrpcEntityReply, ProxyRequest}
import org.slf4j.LoggerFactory


object Serve {

  private final val log = LoggerFactory.getLogger(getClass)
  // When the entity key is made up of multiple fields, this is used to separate them
  final val EntityKeyValueSeparator = "-"
  final val AnyTypeUrlHostName = "type.googleapis.com/"
  final val DescriptorDependencies = Array(
    ScalaPBDescriptorProtos.DescriptorProtoCompanion.javaDescriptor,
    EntitykeyProto.javaDescriptor,
    ProtobufAnyProto.javaDescriptor,
    ProtobufEmptyProto.javaDescriptor,
    AnnotationsProto.javaDescriptor,
    HttpProto.javaDescriptor
  )

  private final val NotFound: PartialFunction[HttpRequest, Future[HttpResponse]] = {
    case req: HttpRequest =>
      log.debug("Not found request: " + req.getUri())
      Future.successful(HttpResponse(StatusCodes.NotFound))
  }

  private final object ReplySerializer extends ProtobufSerializer[GrpcEntityReply] {
    override final def serialize(reply: GrpcEntityReply): ByteString =
      if (reply.payload.isEmpty) {
        ByteString.empty
      } else {
        ByteString.fromArrayUnsafe(reply.payload.toByteArray)
      }

    override final def deserialize(bytes: ByteString): GrpcEntityReply =
      GrpcEntityReply(
        if (bytes.isEmpty) ProtobufByteString.EMPTY
        else ProtobufByteString.readFrom(bytes.iterator.asInputStream)
      )
  }

  final def createEntityIdExtractorFor(desc: Descriptor): DynamicMessage => String = {
    /**
    * ScalaPB doesn't do this conversion for us unfortunately.
    * By doing it, we can use EntitykeyProto.entityKey.get() to read the entity key nicely.
    */
    def convertFieldOptions(field: FieldDescriptor): ScalaPBDescriptorProtos.FieldOptions = {
      ScalaPBDescriptorProtos.
      FieldOptions.
      fromJavaProto(field.toProto.getOptions).
      withUnknownFields(scalapb.UnknownFieldSet(field.getOptions.getUnknownFields.asMap.asScala.map {
          case (idx, f) => idx.toInt -> scalapb.UnknownFieldSet.Field(
            varint          = f.getVarintList.asScala.map(_.toLong),
            fixed64         = f.getFixed64List.asScala.map(_.toLong),
            fixed32         = f.getFixed32List.asScala.map(_.toInt),
            lengthDelimited = f.getLengthDelimitedList.asScala
          )
        }.toMap))
    }

    val fields = desc.getFields.iterator.asScala.
                   filter(field => EntitykeyProto.entityKey.get(convertFieldOptions(field))).
                   toArray.sortBy(_.getIndex)

    fields.length match {
      case 0 => throw new IllegalStateException(s"No field marked with [(cloudstate.entity_key) = true] found for in type ${desc.getName}")
      case 1 =>
        val f = fields.head
        (dm: DynamicMessage) => dm.getField(f).toString
      case _ =>
        (dm: DynamicMessage) => fields.iterator.map(dm.getField).mkString(EntityKeyValueSeparator)
    }
  }

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[GrpcEntityCommand] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName
    private[this] final val extractId = createEntityIdExtractorFor(desc)

    // Should not be used in practice
    override final def serialize(command: GrpcEntityCommand): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): GrpcEntityCommand = {
      GrpcEntityCommand(entityId = extractId(DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)),
        name = commandName,
        payload = Some(ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))))
    }
  }

  final class RequestMessageExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
    override final def entityId(message: Any): String = message match {
      case r: ProxyRequest => r.command.fold("")(_.entityId)
    }
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    val (pkg, name) = Names.splitPrev(serviceName)
    Some(descriptor).filter(_.getPackage == pkg).map(_.findServiceByName(name))
  }

  def createRoute(stateManager: ActorRef, statsCollector: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
    descriptorSet.getFileList.iterator.asScala.map(
      fdp => FileDescriptor.buildFrom(fdp, DescriptorDependencies, true)
    ).map(
      descriptor => extractService(spec.serviceName, descriptor).map(
                      service =>
                       compileProxy(stateManager, statsCollector, proxyParallelism, relayTimeout, service) orElse // Fast path
                       handleNetworkProbe() orElse
                       Reflection.serve(descriptor) orElse // Cheap path
                       HttpApi.serve(stateManager, relayTimeout, service) orElse // Slow path
                       NotFound // No match. TODO: Consider having the caller of this method deal with this condition
                    )
    ).collectFirst({ case Some(route) => route })
      .getOrElse(throw new Exception(s"Service ${spec.serviceName} not found in descriptors!"))
  }

  def handleNetworkProbe(): PartialFunction[HttpRequest, Future[HttpResponse]] = Function.unlift { req =>
    req.headers.find(_.name.equalsIgnoreCase("K-Network-Probe")).map { header =>
      Future.successful(header.value match {
        case "queue" => HttpResponse(entity = HttpEntity("queue"))
        case other => HttpResponse(status = StatusCodes.BadRequest, entity = HttpEntity(s"unexpected probe header value: $other"))
      })
    }
  }

  private[this] final def compileProxy(stateManager: ActorRef, statsCollector: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getFullName
    val rpcMethodSerializers = serviceDesc.getMethods.iterator.asScala.map(
      d => (Path / serviceName / d.getName, new CommandSerializer(d.getName, d.getInputType))
    ).toMap
    val mapRequestFailureExceptions: (ActorSystem => PartialFunction[Throwable, Status]) = {
      val pf: PartialFunction[Throwable, Status] = {
        case CommandFailure(msg) => Status.UNKNOWN.augmentDescription(msg)
      }
      _ => pf
    }

    {
      case req: HttpRequest if rpcMethodSerializers.contains(req.uri.path) =>
        val startTime = System.nanoTime()
        implicit val askTimeout = relayTimeout
        val responseCodec = Codecs.negotiate(req)
        unmarshalStream(req)(rpcMethodSerializers(req.uri.path), mat).
          map(_.mapAsync(proxyParallelism) { command =>
            statsCollector ! StatsCollector.RequestReceived

            val request = ProxyRequest(Some(command))
            (stateManager ? request).mapTo[GrpcEntityReply]
              .transform { result =>
                statsCollector ! StatsCollector.ResponseSent(System.nanoTime() - startTime)
                result
              }
          }).
          map(e => marshalStream(e, mapRequestFailureExceptions)(ReplySerializer, mat, responseCodec, sys)).
          recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }
}

private[proxy] object Names {
  final def splitPrev(name: String): (String, String) = {
    val dot = name.lastIndexOf('.')
    if (dot >= 0) {
      (name.substring(0, dot), name.substring(dot + 1))
    } else {
      ("", name)
    }
  }

  final def splitNext(name: String): (String, String) = {
    val dot = name.indexOf('.')
    if (dot >= 0) {
      (name.substring(0, dot), name.substring(dot + 1))
    } else {
      (name, "")
    }
  }
}
