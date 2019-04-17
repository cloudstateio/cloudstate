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
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{Descriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor, FieldDescriptor}
import com.lightbend.statefulserverless.grpc._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor


object Serve {

  private val EntityKeyOptionNumber = 50002 // See src/main/protoentitykey.proto
  // When the entity key is made up of multiple fields, this is used to separate them
  private val EntityKeyValueSeparator = "-"
  private val AnyTypeUrlHostName = "type.googleapis.com/"

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

  private final class CommandSerializer(private[this] final val desc: Descriptor) extends ProtobufSerializer[Command] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName
    private[this] final val entityKeys = desc.getFields.asScala.filter(
      _.getOptions.getUnknownFields.hasField(EntityKeyOptionNumber) // todo do we need to check if the value is true?
    ).toArray.sortBy(_.getIndex) // TODO specify order explicitly for maintainability?

    // Should not be used in practice
    override final def serialize(command: Command): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    private[this] final def entityIdFrom(field: FieldDescriptor, dm: DynamicMessage): String =
      "(" + field + "," + dm.getField(field) + ")" // FIXME: Is this correct?

    override final def deserialize(bytes: ByteString): Command = {
      val dm = DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)

      val entityId = 
        if (entityKeys.length == 1) entityIdFrom(entityKeys(0), dm) // Fast path 
        else entityKeys.iterator.map(k => entityIdFrom(k, dm)).mkString(EntityKeyValueSeparator)

      val payload = ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))

      // Use of named parameters here is important, Command is a generated class and if the
      // order of fields changes, that could silently break this code
      // Note, we're not setting the command id. We'll leave it up to the StateManager actor
      // to generate an id that is unique per session.
      Command(entityId = entityId, name = desc.getName, payload = Some(payload))
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
    def this(method: MethodDescriptor) = this(method.getName, new CommandSerializer(method.getInputType), ReplySerializer)
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    // todo - this really needs to be on a FileDescriptorSet, not a single FileDescriptor
    val dot = serviceName.lastIndexOf(".")
    val (pkg, name) = if (dot >= 0) {
      (serviceName.substring(0, dot), serviceName.substring(dot + 1))
    } else {
      ("", serviceName)
    }

    if (pkg == descriptor.getPackage) {
      Some(descriptor.findServiceByName(name))
    } else {
      None
    }
  }

  def createRoute(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptor = FileDescriptor.buildFrom(DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray), Array())

    extractService(spec.serviceName, descriptor) match {
      case None => throw new Exception(s"Service ${spec.serviceName} not found in descriptor!")
      case Some(service) => compileInterface(stateManager, proxyParallelism, relayTimeout, service)
    }
  }

  def compileInterface(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getName
    val implementedEndpoints = serviceDesc.getMethods.iterator.asScala.map(d => (d.getName, new ExposedEndpoint(d))).toMap

    Function.unlift { req: HttpRequest =>
      req.uri.path match {
        // FIXME verify that path matching is compatible with different permutations of service declarations
        case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(endpointName, Path.Empty)))) if implementedEndpoints.keySet.contains(endpointName) ⇒
          import GrpcMarshalling.{marshalStream, unmarshalStream}
          val responseCodec = Codecs.negotiate(req)
          val endpoint = implementedEndpoints(endpointName)
          implicit val askTimeout = relayTimeout

          Some(unmarshalStream(req)(endpoint.unmarshaller, mat).
            map(_.mapAsync(proxyParallelism)(command => (stateManager ? command).mapTo[ProtobufByteString])).
            map(e => marshalStream(e)(endpoint.marshaller, mat, responseCodec, sys)).
            recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys))))
        case _ => None
      }
    }
  }
}
