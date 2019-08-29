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
import akka.grpc.Codecs
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.Uri.Path
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.FileDescriptor
import akka.NotUsed
import _root_.grpc.reflection.v1alpha.reflection._
import com.google.api.annotations.AnnotationsProto

object Reflection {
  private final val ReflectionPath = Path / ServerReflection.name / "ServerReflectionInfo"

  final val AdditionalDescriptors = List(
    AnnotationsProto.javaDescriptor,
    ReflectionProto.javaDescriptor
  ).flatMap(flattenDependencies).distinct

  private def flattenDependencies(descriptor: FileDescriptor): List[FileDescriptor] = {
    descriptor :: descriptor.getDependencies.asScala.toList.flatMap(flattenDependencies)
  }

  def serve(fileDescriptors: Seq[FileDescriptor], services: List[String])(implicit mat: Materializer, sys: ActorSystem): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    implicit val ec: ExecutionContext = mat.executionContext
    import ServerReflection.Serializers._

    val handler = handle((AdditionalDescriptors ++ fileDescriptors).map(fd => fd.getName -> fd).toMap, services)

    {
      case req: HttpRequest if req.uri.path == ReflectionPath =>
        val responseCodec = Codecs.negotiate(req)
        GrpcMarshalling.unmarshalStream(req)(ServerReflectionRequestSerializer, mat)
        .map(_ via handler)
        .map(e => GrpcMarshalling.marshalStream(e, GrpcExceptionHandler.defaultMapper)(ServerReflectionResponseSerializer, mat, responseCodec, sys))
    }
  }

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

  private final def findFileDescForSymbol(symbol: String, fileDescriptors: Map[String, FileDescriptor]): Option[FileDescriptor] =
    fileDescriptors.values.collectFirst {
      case fileDesc if containsSymbol(symbol, fileDesc) => fileDesc
    }

  private final def containsExtension(container: String, number: Int, fileDesc: FileDescriptor): Boolean =
    fileDesc.getExtensions.iterator.asScala.exists(ext => container == ext.getContainingType.getFullName && number == ext.getNumber)

  private final def findFileDescForExtension(container: String, number: Int, fileDescriptors: Map[String, FileDescriptor]): Option[FileDescriptor] =
    fileDescriptors.values.collectFirst {
      case fileDesc if containsExtension(container, number, fileDesc) => fileDesc
    }

  private final def findExtensionNumbersForContainingType(container: String, fileDescriptors: Map[String, FileDescriptor]): List[Int] =
    (for {
      fileDesc <- fileDescriptors.values.iterator
      extension <- fileDesc.getExtensions.iterator.asScala
      if extension.getFullName == container
    } yield extension.getNumber).toList

  private def handle(fileDescriptors: Map[String, FileDescriptor], services: List[String]): Flow[ServerReflectionRequest, ServerReflectionResponse, NotUsed] =
    Flow[ServerReflectionRequest].map(req => {
      import ServerReflectionRequest.{ MessageRequest => In}
      import ServerReflectionResponse.{ MessageResponse => Out}

      val response = req.messageRequest match {
        case In.Empty =>
          Out.Empty
        case In.FileByFilename(fileName) =>
          val list = fileDescriptors.get(fileName).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingSymbol(symbol) =>
          val list = findFileDescForSymbol(symbol, fileDescriptors).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingExtension(ExtensionRequest(container, number)) =>
          val list = findFileDescForExtension(container, number, fileDescriptors).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.AllExtensionNumbersOfType(container) =>
          val list = findExtensionNumbersForContainingType(container, fileDescriptors) // TODO should we throw a NOT_FOUND if we don't know the container type at all?
          Out.AllExtensionNumbersResponse(ExtensionNumberResponse(container, list))
        case In.ListServices(_) =>
          val list = services.map(s => ServiceResponse(s))
          Out.ListServicesResponse(ListServiceResponse(list))
      }
      // TODO Validate assumptions here
      ServerReflectionResponse(req.host, Some(req), response)
    })
}
