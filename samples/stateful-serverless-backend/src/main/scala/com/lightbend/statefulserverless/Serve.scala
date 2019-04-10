package com.lightbend.statefulserverless

import scala.concurrent.{ ExecutionContext, Future }

import akka.grpc.scaladsl
import akka.grpc.scaladsl.{ GrpcExceptionHandler, GrpcMarshalling, ScalapbProtobufSerializer, Metadata, MetadataImpl }
import akka.grpc.{Codecs, GrpcServiceException }
import io.grpc.Status

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, StatusCodes }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.ActorSystem
import akka.stream.Materializer

import com.google.protobuf.descriptor._
import com.lightbend.statefulserverless.grpc._

object Serve {

  def createRoute(spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] =
    spec.proto.flatMap(_.service.find(_.getName == spec.serviceName).map(Serve.compileInterface)).get

  def compileInterface(serviceDesc: ServiceDescriptorProto)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    def relay(req: HttpRequest, method: String): Future[HttpResponse] = {
      // 3. on request
      //    * verify inbound request against the service descriptor
      //    * extract key and package the payload
      //    * forward (ask) the payload to Cluster Sharding
      // 4. establish call to handle-method of user container using Sink.actorRef
      //    * Actor needs to establish stream to user function (HOW ARE THESE SHARED?)
      // 5. forward snapshot (Init) + Events at postStart to the ActorRef
      // 6. forward Command to the ActorRef
      // 7. assume that responses are 1-to-1 to Commands, track outstanding responses
      // 8. as Responses come back, store any events enclosed, optionally store any snapshot enclosed, then respond with the payload
      /*
        GrpcMarshalling.unmarshalStream(request)(EntityStreamInSerializer, mat)
          .map(implementation.handle(_))
          .map(e => GrpcMarshalling.marshalStream(e, eHandler)(EntityStreamOutSerializer, mat, responseCodec, system))
      */
      val responseCodec = Codecs.negotiate(req)

      // How do we make sure that requests *typically* hit the right shard?


      ???
    }

    {
      val serviceName = serviceDesc.getName
      val implementedEndpoints = serviceDesc.method.iterator.map(_.getName).toSet

      (req: HttpRequest) => req.uri.path match {
        case Path.Slash(Segment(`serviceName`, Path.Slash(Segment(method, Path.Empty)))) if implementedEndpoints.contains(method) ⇒
          relay(req, method).recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
      }
    }
  }
}
