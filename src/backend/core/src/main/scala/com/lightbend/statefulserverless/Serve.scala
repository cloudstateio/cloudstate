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
import com.google.protobuf.empty.{Empty => ProtobufEmpty, EmptyProto => ProtobufEmptyProto}
import com.google.protobuf.any.{Any => ProtobufAny, AnyProto => ProtobufAnyProto}
import com.google.protobuf.Descriptors.{Descriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.lightbend.statefulserverless.grpc._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor


object Serve {
  // TODO load this value from the generated Option
  private final val EntityKeyOptionNumber = 50002 // See src/main/protoentitykey.proto
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

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[Command] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName
    private[this] final val entityKeys = desc.getFields.asScala.filter(
      _.getOptions.getUnknownFields.hasField(EntityKeyOptionNumber)
    ).toArray.sortBy(_.getIndex)

    require(entityKeys.nonEmpty, s"No field marked with [(com.lightbend.statefulserverless.grpc.entity_key) = true] found for $commandName")

    // Should not be used in practice
    override final def serialize(command: Command): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): Command = {
      val dm = DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)

      val entityId =
        if (entityKeys.length == 1) dm.getField(entityKeys(0)).toString // Fast path
        else entityKeys.iterator.map(dm.getField).mkString(EntityKeyValueSeparator)

      val payload = ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))

      // Use of named parameters here is important, Command is a generated class and if the
      // order of fields changes, that could silently break this code
      // Note, we're not setting the command id. We'll leave it up to the StateManager actor
      // to generate an id that is unique per session.
      Command(entityId = entityId, name = commandName, payload = Some(payload))
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
      DescriptorProtos.FileDescriptorProto.parseFrom(
        spec.proto.get.toByteArray),
        Array(EntitykeyProto.javaDescriptor,
              ProtobufAnyProto.javaDescriptor,
              ProtobufEmptyProto.javaDescriptor),
      true)

    // Debug:
    //println(DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray).toString)

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
                  map(e => marshalStream(e)(endpoint.marshaller, mat, responseCodec, sys))
            }

            Some(future.recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys))))
          case _ =>
            None
      }
    }
  }
}
