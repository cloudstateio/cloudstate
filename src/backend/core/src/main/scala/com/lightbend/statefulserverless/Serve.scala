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

package com.lightbend.statefulserverless

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import akka.grpc.{Codecs, ProtobufSerializer}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.{ActorRef, ActorSystem}
import akka.util.{ByteString, Timeout}
import akka.pattern.ask
import akka.stream.Materializer
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString}
import com.google.protobuf.empty.{EmptyProto => ProtobufEmptyProto}
import com.google.protobuf.any.{Any => ProtobufAny, AnyProto => ProtobufAnyProto}
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{descriptor => ScalaPBDescriptorProtos}
import com.lightbend.statefulserverless.grpc._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import com.lightbend.statefulserverless.StateManager.CommandFailure
import io.grpc.Status


object Serve {
  // When the entity key is made up of multiple fields, this is used to separate them
  private final val EntityKeyValueSeparator = "-"
  private final val AnyTypeUrlHostName = "type.googleapis.com/"

  private final object ReplySerializer extends ProtobufSerializer[ProtobufByteString] {
    override final def serialize(pbBytes: ProtobufByteString): ByteString =
      if (pbBytes.isEmpty) {
        ByteString.empty
      } else {
        ByteString.fromArrayUnsafe(pbBytes.toByteArray())
      }
    override final def deserialize(bytes: ByteString): ProtobufByteString =
      if (bytes.isEmpty) {
        ProtobufByteString.EMPTY
      } else {
        ProtobufByteString.readFrom(bytes.iterator.asInputStream)
      }
  }

  /**
    * ScalaPB doesn't do this conversion for us unfortunately.
    *
    * By doing it, we can use EntitykeyProto.entityKey.get() to read the entity key nicely.
    */
  private[this] final def convertFieldOptions(field: FieldDescriptor): ScalaPBDescriptorProtos.FieldOptions = {
    val fields =
      scalapb.UnknownFieldSet(field.getOptions.getUnknownFields.asMap.asScala.map {
        case (idx, f) => idx.toInt -> scalapb.UnknownFieldSet.Field(
          varint          = f.getVarintList.asScala.map(_.toLong),
          fixed64         = f.getFixed64List.asScala.map(_.toLong),
          fixed32         = f.getFixed32List.asScala.map(_.toInt),
          lengthDelimited = f.getLengthDelimitedList.asScala
        )
      }.toMap)

    ScalaPBDescriptorProtos.FieldOptions.fromJavaProto(field.toProto.getOptions).withUnknownFields(fields)
  }

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[Command] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName
    private[this] final val extractId = {
      val fields = desc.getFields.iterator.asScala.
                     filter(field => EntitykeyProto.entityKey.get(convertFieldOptions(field))).
                     toArray.sortBy(_.getIndex)

      fields.length match {
        case 0 => throw new IllegalStateException(s"No field marked with [(com.lightbend.statefulserverless.grpc.entity_key) = true] found for $commandName")
        case 1 =>
          val f = fields.head
          (dm: DynamicMessage) => dm.getField(f).toString
        case _ =>
          (dm: DynamicMessage) => fields.iterator.map(dm.getField).mkString(EntityKeyValueSeparator)
      }
    }

    // Should not be used in practice
    override final def serialize(command: Command): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): Command = {
      // Use of named parameters here is important, Command is a generated class and if the
      // order of fields changes, that could silently break this code
      // Note, we're not setting the command id. We'll leave it up to the StateManager actor
      // to generate an id that is unique per session.
      Command(entityId = extractId(DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)),
              name = commandName,
              payload = Some(ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))))
    }
  }

  final class CommandMessageExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
    override final def entityId(message: Any): String = message match {
      case c: Command => c.entityId
    }
  }

  private final class ExposedEndpoint private[this](
    final val name: String,
    final val unmarshaller: ProtobufSerializer[Command],
    final val marshaller: ProtobufSerializer[ProtobufByteString]) {
    def this(method: MethodDescriptor) = this(method.getName, new CommandSerializer(method.getName, method.getInputType), ReplySerializer)
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    // todo - this really needs to be on a FileDescriptorSet, not a single FileDescriptor
    val dot = serviceName.lastIndexOf(".")
    val (pkg, name) = if (dot >= 0) {
      (serviceName.substring(0, dot), serviceName.substring(dot + 1))
    } else {
      ("", serviceName)
    }

    Some(descriptor).filter(_.getPackage == pkg).map(_.findServiceByName(name))
  }

  def createRoute(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptor = FileDescriptor.buildFrom(
      // It would be nice if we could just use the following line, but turns out ScalaPB doesn't copy unknown fields,
      // and we need that if we want to access the entity key later.
      // com.google.protobuf.descriptor.FileDescriptorProto.toJavaProto(spec.proto.get)
      DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray),
        Array(EntitykeyProto.javaDescriptor,
              ProtobufAnyProto.javaDescriptor,
              ProtobufEmptyProto.javaDescriptor),
        true)

    extractService(spec.serviceName, descriptor) match {
      case None => throw new Exception(s"Service ${spec.serviceName} not found in descriptor!")
      case Some(service) =>
        compileProxy(stateManager, proxyParallelism, relayTimeout, service) orElse {
          case req: HttpRequest => Future.successful(HttpResponse(StatusCodes.NotFound)) // TODO do we need this?
        }
    }
  }

  private[this] final def compileProxy(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getFullName
    val implementedEndpoints = serviceDesc.getMethods.iterator.asScala.map(d => (d.getName, new ExposedEndpoint(d))).toMap.withDefault(null)
    val mapRequestFailureExceptions: (ActorSystem => PartialFunction[Throwable, Status]) = {
      val pf: PartialFunction[Throwable, Status] = {
        case CommandFailure(msg) => Status.UNKNOWN.augmentDescription(msg)
      }
      _ => pf
    }

    Function.unlift { req: HttpRequest =>
      req.uri.path match {
        case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(endpointName, Path.Empty)))) ⇒
          val future =
            implementedEndpoints(endpointName) match {
              case null => Future.failed(new NotImplementedError(s"Not implemented: $endpointName"))
              case endpoint =>
                import GrpcMarshalling.{marshalStream, unmarshalStream}
                val responseCodec = Codecs.negotiate(req)
                implicit val askTimeout = relayTimeout

                unmarshalStream(req)(endpoint.unmarshaller, mat).
                  map(_.mapAsync(proxyParallelism)(command => (stateManager ? command).mapTo[ProtobufByteString])).
                  map(e => marshalStream(e, mapRequestFailureExceptions)(endpoint.marshaller, mat, responseCodec, sys))
            }

            Some(future.recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys))))
          case _ =>
            None
      }
    }
  }
}
