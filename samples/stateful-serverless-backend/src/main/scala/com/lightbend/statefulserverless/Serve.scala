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
import com.google.protobuf.Descriptors.{ Descriptor, FileDescriptor, ServiceDescriptor }
import com.lightbend.statefulserverless.grpc._

object Serve {
  private final object ByteArraySerializer extends ProtobufSerializer[Array[Byte]] {
    override final def serialize(ab: Array[Byte]): ByteString = ByteString(ab)
    override final def deserialize(bytes: ByteString): Array[Byte] = bytes.toArray
  }

  private final class DynamicSerializer(private[this] final val desc: Descriptor) extends ProtobufSerializer[DynamicMessage] {
    override final def serialize(dm: DynamicMessage): ByteString = ByteString(dm.toByteArray)
    override final def deserialize(bytes: ByteString): DynamicMessage = DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)
  }

  def createRoute(stateManager: ActorRef)(spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] =
    compileInterface(stateManager)(
      FileDescriptor.buildFrom(DescriptorProtos.FileDescriptorProto.parseFrom(spec.proto.get.toByteArray), Array()). // FIXME avoid this weird conversion
      findServiceByName(spec.serviceName)
    )

  def compileInterface(stateManager: ActorRef)(serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    import GrpcMarshalling.{ marshalStream, unmarshalStream }
    val serviceName = serviceDesc.getName
    val implementedEndpoints = serviceDesc.
                                 getMethods.
                                 iterator.
                                 asScala.
                                 map(d => (d.getName, new DynamicSerializer(d.getInputType))).
                                 foldLeft(Map[String, DynamicSerializer]()) { _ + _ }
    
    implicit val timeout = Timeout(5.seconds) // FIXME load from config

    def relayMessage(endpoint: String)(msg: DynamicMessage): Future[Array[Byte]] = {
      val id = ???// FIXME extract id from msg
      val ep = endpoint
      val pl = ??? // FIXME extract payload from msg
      (stateManager ? Command.of(id, ep, Some(pl))).mapTo[Array[Byte]]
    }

    (req: HttpRequest) => req.uri.path match {
      case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(endpoint, Path.Empty)))) if implementedEndpoints.keySet.contains(endpoint) ⇒
        val responseCodec = Codecs.negotiate(req)
        val serializer = implementedEndpoints(endpoint)

        unmarshalStream(req)(serializer, mat).
          map(_.mapAsync(1)(relayMessage(endpoint))).
          map(e => marshalStream(e)(ByteArraySerializer, mat, responseCodec, sys)).
          recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }
}
