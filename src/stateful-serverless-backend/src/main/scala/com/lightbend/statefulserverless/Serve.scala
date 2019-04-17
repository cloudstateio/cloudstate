package com.lightbend.statefulserverless

import java.util.concurrent.ThreadLocalRandom

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import akka.grpc.{Codecs, ProtobufSerializer}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.{ActorRef, ActorSystem}
import akka.util.{ByteString, Timeout}
import akka.pattern.ask
import akka.stream.Materializer
import com.google.protobuf.{ DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString }
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{Descriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.lightbend.statefulserverless.grpc._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor


object Serve {

  private val EntityKeyOptionNumber = 50002
  // When the entity key is made up of multiple fields, this is used to separate them
  private val EntityKeyValueSeparator = "-"
  private val AnyTypeUrlHostName = "type.googleapis.com/"

  private final object ByteStringSerializer extends ProtobufSerializer[ByteString] {
    override def serialize(bytes: ByteString): ByteString = bytes

    override def deserialize(bytes: ByteString): ByteString = bytes
  }

  private final class CommandSerializer(private val desc: Descriptor) extends ProtobufSerializer[Command] {

    private val entityKeys = desc.getFields.asScala.filter { field =>
      // todo do we need to check if the value is true?
      field.getOptions.getUnknownFields.hasField(EntityKeyOptionNumber)
    }.toList.sortBy(_.getIndex)

    // Should not be used in practice
    override def serialize(command: Command): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }


    override def deserialize(bytes: ByteString): Command = {
      val dm = DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)

      val entityId = entityKeys.map(desc => (desc, dm.getField(desc)))
        .mkString(EntityKeyValueSeparator)
      val payload = ProtobufAny(
        typeUrl = AnyTypeUrlHostName + desc.getFullName,
        value = ProtobufByteString.copyFrom(bytes.asByteBuffer)
      )
      // Use of named parameters here is important, Command is a generated class and if the
      // order of fields changes, that could silently break this code
      // Note, we're not setting the command id. We'll leave it up to the StateManager actor
      // to generate an id that is unique per session.
      Command(
        entityId = entityId,
        name = desc.getName,
        payload = Some(payload)
      )
    }
  }

  final class CommandMessageExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
    override def entityId(message: Any): String = message match {
      case c: Command => c.entityId
    }
  }

  private trait Endpoint {
    def name: String

    def unmarshaller: ProtobufSerializer[Command]

    def marshaller: ProtobufSerializer[ByteString]
  }

  private final class LoadedEndpoint(override val name: String,
    override val unmarshaller: ProtobufSerializer[Command],
    override val marshaller: ProtobufSerializer[ByteString]) extends Endpoint {
    def this(method: MethodDescriptor) = this(method.getName, new CommandSerializer(method.getInputType), ByteStringSerializer)
  }

  def createRoute(stateManager: ActorRef)(spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext, requestTimeout: Timeout): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptor = FileDescriptor.buildFrom(DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray), Array())

    // todo - this really needs to be on a FileDescriptorSet, not a single FileDescriptor
    val dot = spec.serviceName.lastIndexOf(".")
    val (pkg, name) = if (dot >= 0) {
      (spec.serviceName.substring(0, dot), spec.serviceName.substring(dot + 1))
    } else {
      ("", spec.serviceName)
    }

    val service = if (pkg != descriptor.getPackage) {
      null
    } else {
      descriptor.findServiceByName(name)
    }

    if (service == null) {
      throw new Exception("Service " + spec.serviceName + " not found in descriptor!")
    }

    compileInterface(stateManager)(service)
  }

  def compileInterface(stateManager: ActorRef)(serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext, requestTimeout: Timeout): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getName
    val implementedEndpoints = serviceDesc.getMethods.iterator.asScala.map(d => (d.getName, new LoadedEndpoint(d))).toMap

    Function.unlift { req: HttpRequest =>
      req.uri.path match {
        // FIXME verify that path matching is compatible with different permutations of service declarations
        case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(endpointName, Path.Empty)))) if implementedEndpoints.keySet.contains(endpointName) ⇒
          import GrpcMarshalling.{marshalStream, unmarshalStream}
          val responseCodec = Codecs.negotiate(req)
          val endpoint = implementedEndpoints(endpointName)

          Some(unmarshalStream(req)(endpoint.unmarshaller, mat).
            // mapAsync 1? I don't think it's necessary to force commands to be handled one at a time.
            map(_.mapAsync(1)(command => (stateManager ? command).mapTo[ByteString])).
            map(e => marshalStream(e)(endpoint.marshaller, mat, responseCodec, sys)).
            recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys))))
        case _ => None
      }
    }
  }
}
