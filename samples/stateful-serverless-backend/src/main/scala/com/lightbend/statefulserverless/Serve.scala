package com.lightbend.statefulserverless

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import akka.grpc.scaladsl
import akka.grpc.scaladsl.{ GrpcExceptionHandler, GrpcMarshalling, ScalapbProtobufSerializer, Metadata, MetadataImpl }
import akka.grpc.{ Codecs, GrpcServiceException, ProtobufSerializer }

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.{ ActorSystem, ActorRef }
import akka.util.{ ByteString, Timeout }
import akka.pattern.{ ask, pipe }
import akka.stream.Materializer

import com.google.protobuf.{ DynamicMessage, DescriptorProtos }
import com.google.protobuf.Descriptors.{ Descriptor, MethodDescriptor, FileDescriptor, ServiceDescriptor }
import com.lightbend.statefulserverless.grpc._

import akka.cluster.sharding.ShardRegion

object Serve {

  private[this] final object ByteArraySerializer extends ProtobufSerializer[Array[Byte]] {
    override final def serialize(ab: Array[Byte]): ByteString = ByteString(ab)
    override final def deserialize(bytes: ByteString): Array[Byte] = bytes.toArray
  }

  private[this] final class DynamicSerializer(private[this] final val desc: Descriptor) extends ProtobufSerializer[DynamicMessage] {
    override final def serialize(dm: DynamicMessage): ByteString = ByteString(dm.toByteArray)
    override final def deserialize(bytes: ByteString): DynamicMessage = DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)
  }

  final val commandIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: Command => (cmd.entityId, cmd) // TODO validate this assumption
  }

  final val commandShardIdResolver: ShardRegion.ExtractShardId = {
    case cmd: Command => cmd.entityId // FIXME use something else?
    // case ShardRegion.StartEntity(id) => FIXME use `rememberEntities = true` when booting the sharding?
  }

  private trait Endpoint {
    def name: String
    def unmarshaller: ProtobufSerializer[DynamicMessage]
    def marshaller: ProtobufSerializer[Array[Byte]]
    def toCommand(msg: DynamicMessage): Command
  }

  private final class LoadedEndpoint(override final val name: String,
    override final val unmarshaller: ProtobufSerializer[DynamicMessage],
    override final val marshaller: ProtobufSerializer[Array[Byte]]) extends Endpoint {
    final def this(method: MethodDescriptor) = this(method.getName, new DynamicSerializer(method.getInputType), ByteArraySerializer)
    final override def toCommand(msg: DynamicMessage): Command = {
      val cid = ??? // FIXME extract id from msg or potentially generate ID?
      val eid = ??? // FIXME extract entity identifier from msg
      val pay = ??? // FIXME extract payload from msg
      Command.of(eid, cid, name, Some(pay))
    }
  }

  def createRoute(stateManager: ActorRef)(spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext, requestTimeout: Timeout): PartialFunction[HttpRequest, Future[HttpResponse]] =
    compileInterface(stateManager)(
      FileDescriptor.buildFrom(DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray), Array()). // FIXME avoid this weird conversion
      findServiceByName(spec.serviceName)
    )

  def compileInterface(stateManager: ActorRef)(serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext, requestTimeout: Timeout): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getName
    val implementedEndpoints = serviceDesc.
                                 getMethods.
                                 iterator.
                                 asScala.
                                 map(d => (d.getName, new LoadedEndpoint(d))).
                                 toMap

    (req: HttpRequest) => req.uri.path match {
      // FIXME verify that path matching is compatible with different permutations of service declarations
      case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(endpointName, Path.Empty)))) if implementedEndpoints.keySet.contains(endpointName) ⇒
        import GrpcMarshalling.{ marshalStream, unmarshalStream }
        val responseCodec = Codecs.negotiate(req)
        val endpoint = implementedEndpoints(endpointName)

        unmarshalStream(req)(endpoint.unmarshaller, mat).
          map(_.mapAsync(1)(dm => (stateManager ? endpoint.toCommand(dm)).mapTo[Array[Byte]])).
          map(e => marshalStream(e)(endpoint.marshaller, mat, responseCodec, sys)).
          recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }
}
