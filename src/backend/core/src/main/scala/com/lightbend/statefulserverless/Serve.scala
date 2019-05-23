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
import GrpcMarshalling.{marshalStream, unmarshalStream}
import akka.grpc.{Codecs, ProtobufSerializer, GrpcServiceException}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.{ActorRef, ActorSystem}
import akka.util.{ByteString, Timeout}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString}
import com.google.protobuf.empty.{EmptyProto => ProtobufEmptyProto}
import com.google.protobuf.any.{Any => ProtobufAny, AnyProto => ProtobufAnyProto}
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{descriptor => ScalaPBDescriptorProtos}
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import com.lightbend.statefulserverless.StateManager.CommandFailure
import com.lightbend.statefulserverless.grpc.{EntitySpec, EntitykeyProto}
import io.grpc.Status
import com.lightbend.statefulserverless.internal.grpc.{GrpcEntityCommand, Request}

import scala.util.Success


object Serve {
  // When the entity key is made up of multiple fields, this is used to separate them
  private final val EntityKeyValueSeparator = "-"
  private final val AnyTypeUrlHostName = "type.googleapis.com/"
  private final val DescriptorDependencies = Array(
    ScalaPBDescriptorProtos.DescriptorProtoCompanion.javaDescriptor,
    EntitykeyProto.javaDescriptor,
    ProtobufAnyProto.javaDescriptor,
    ProtobufEmptyProto.javaDescriptor
  )

  private final val NotFound: PartialFunction[HttpRequest, Future[HttpResponse]] = {
    case req: HttpRequest => Future.successful(HttpResponse(StatusCodes.NotFound))
  }

  private final val ActivatorProxyName = "activator"

  final class KnativeProxyHeader(token: String) extends ModeledCustomHeader[KnativeProxyHeader] {
    override def renderInRequests = true
    override def renderInResponses = true
    override val companion = KnativeProxyHeader
    override def value: String = token

    /**
      * Whether the proxy is the Knative activator proxy
      */
    def isActivator = value == ActivatorProxyName
  }
  object KnativeProxyHeader extends ModeledCustomHeaderCompanion[KnativeProxyHeader] {
    override val name = "K-Proxy-Request"
    override def parse(value: String) = Success(new KnativeProxyHeader(value))
  }

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

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[GrpcEntityCommand] {
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
      case c: Request => c.command.fold("")(_.entityId)
    }
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    val (pkg, name) = Names.splitPrev(serviceName)
    Some(descriptor).filter(_.getPackage == pkg).map(_.findServiceByName(name))
  }

  def createRoute(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
    descriptorSet.getFileList.iterator.asScala.map(
      fdp => FileDescriptor.buildFrom(fdp, DescriptorDependencies, true)
    ).map(
      descriptor => extractService(spec.serviceName, descriptor).map(
                      service =>
                       compileProxy(stateManager, proxyParallelism, relayTimeout, service) orElse
                       Reflection.serve(descriptor) orElse
                       NotFound
                    )
    ).collectFirst({ case Some(route) => route })
     .getOrElse(throw new Exception(s"Service ${spec.serviceName} not found in descriptors!"))
  }

  private[this] final def compileProxy(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
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

    { case req: HttpRequest if rpcMethodSerializers.contains(req.uri.path) =>
        implicit val askTimeout = relayTimeout
        val responseCodec = Codecs.negotiate(req)
        unmarshalStream(req)(rpcMethodSerializers(req.uri.path), mat).
          map(_.mapAsync(proxyParallelism) { command =>
            val request = Request(Some(command), req.header[KnativeProxyHeader].exists(_.isActivator))
            (stateManager ? request).mapTo[ProtobufByteString]
          }).
          map(e => marshalStream(e, mapRequestFailureExceptions)(ReplySerializer, mat, responseCodec, sys)).
          recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }
}

private[statefulserverless] object Names {
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

object Reflection {
  import _root_.grpc.reflection.v1alpha._

  private final val ReflectionPath = Path / ServerReflection.name / "ServerReflectionInfo"

  def serve(fileDesc: FileDescriptor)(implicit mat: Materializer, sys: ActorSystem): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    implicit val ec: ExecutionContext = mat.executionContext
    import ServerReflection.Serializers._

    val handler = handle(fileDesc)

    {
      case req: HttpRequest if req.uri.path == ReflectionPath =>
        val responseCodec = Codecs.negotiate(req)
        GrpcMarshalling.unmarshalStream(req)(ServerReflectionRequestSerializer, mat)
        .map(_ via handler)
        .map(e => GrpcMarshalling.marshalStream(e, GrpcExceptionHandler.defaultMapper)(ServerReflectionResponseSerializer, mat, responseCodec, sys))
    }
  }

  private final def findFileDescForName(name: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (name == fileDesc.getName) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForName(name, fd)).find(_.isDefined).flatten

  private final def containsSymbol(symbol: String, fileDesc: FileDescriptor): Boolean =
    (symbol.startsWith(fileDesc.getPackage)) && // Ensure package match first
    (Names.splitNext(if (fileDesc.getPackage.isEmpty) symbol else symbol.drop(fileDesc.getPackage.length + 1)) match {
      case ("", "") => false
      case (typeOrService, "") =>
      //fileDesc.findEnumTypeByName(typeOrService) != null || // TODO investigate if this is expected
        fileDesc.findMessageTypeByName(typeOrService) != null ||
        fileDesc.findServiceByName(typeOrService) != null
      case (service, method) =>
        Option(fileDesc.findServiceByName(service)).exists(_.findMethodByName(method) != null)
    })

  private final def findFileDescForSymbol(symbol: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsSymbol(symbol, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForSymbol(symbol, fd)).find(_.isDefined).flatten

  private final def containsExtension(container: String, number: Int, fileDesc: FileDescriptor): Boolean =
    fileDesc.getExtensions.iterator.asScala.exists(ext => container == ext.getContainingType.getFullName && number == ext.getNumber)

  private final def findFileDescForExtension(container: String, number: Int, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsExtension(container, number, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForExtension(container, number, fd)).find(_.isDefined).flatten

  private final def findExtensionNumbersForContainingType(container: String, fileDesc: FileDescriptor): List[Int] = 
    fileDesc.getDependencies.iterator.asScala.foldLeft(
      fileDesc.getExtensions.iterator.asScala.collect({ case ext if ext.getFullName == container => ext.getNumber }).toList
    )((list, fd) => findExtensionNumbersForContainingType(container, fd) ::: list)

  private def handle(fileDesc: FileDescriptor): Flow[ServerReflectionRequest, ServerReflectionResponse, NotUsed] =
    Flow[ServerReflectionRequest]/*DEBUG: .alsoTo(Sink.foreach(println(_)))*/.map(req => {
      import ServerReflectionRequest.{ MessageRequest => In}
      import ServerReflectionResponse.{ MessageResponse => Out}

      val response = req.messageRequest match {
        case In.Empty =>
          Out.Empty
        case In.FileByFilename(fileName) =>
          val list = findFileDescForName(fileName, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingSymbol(symbol) =>
          val list = findFileDescForSymbol(symbol, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingExtension(ExtensionRequest(container, number)) =>
          val list = findFileDescForExtension(container, number, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.AllExtensionNumbersOfType(container) =>
          val list = findExtensionNumbersForContainingType(container, fileDesc) // TODO should we throw a NOT_FOUND if we don't know the container type at all?
          Out.AllExtensionNumbersResponse(ExtensionNumberResponse(container, list))
        case In.ListServices(_)              =>
          val list = fileDesc.getServices.iterator.asScala.map(s => ServiceResponse(s.getFullName)).toList
          Out.ListServicesResponse(ListServiceResponse(list))
      }
      // TODO Validate assumptions here
      ServerReflectionResponse(req.host, Some(req), response)
    })// DEBUG: .alsoTo(Sink.foreach(println(_)))
}
