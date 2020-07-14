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

import java.util.Base64

import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.internal.{Codecs, GrpcProtocolNative, GrpcResponseHelpers}
import akka.http.scaladsl.model.HttpEntity.{Chunk, LastChunk}
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MessageEntity,
  ResponseEntity
}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.google.protobuf.any.{Any => ProtobufAny}

import scala.concurrent.{ExecutionContext, Future}

/*
 * Implements https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md, which on the server side, basically means
 * translating the trailers last chunk to a message, and optionally base64 encoding every message.
 */
object GrpcWebSupport {

  private val GrpcWebContentTypeParser = "application/grpc-web(-text)?(?:\\+(\\w+))?".r

  def wrapGrpcHandler(
      partial: PartialFunction[HttpRequest, Future[HttpResponse]]
  )(implicit ec: ExecutionContext, system: ActorSystem): PartialFunction[HttpRequest, Future[HttpResponse]] =
    Function.unlift { request =>
      request.entity.contentType.mediaType.value match {
        case GrpcWebContentTypeParser(text, proto) if request.method == HttpMethods.POST =>
          val newContentType = ContentType
            .parse(if (proto == null) {
              "application/grpc"
            } else {
              s"application/grpc+$proto"
            })
            .right
            .get

          val decoded = if (text != null) {
            // Body is base64 encoded
            decodeText(newContentType, request.entity)
          } else request.entity.withContentType(newContentType)

          val newRequest = request.withEntity(decoded)
          partial
            .andThen { futureResponse =>
              Some(futureResponse.map(response => encode(request, response, proto)))
            }
            .applyOrElse(newRequest, (_: HttpRequest) => None)

        case _ =>
          partial.andThen(Some(_)).applyOrElse(request, (_: HttpRequest) => None)
      }
    }

  private def encode(request: HttpRequest, response: HttpResponse, proto: String) = {

    // Should be a chunked entity, if it's not, we can just pass as is
    val withEncodedTrailer = response.entity match {
      case HttpEntity.Chunked(_, data) =>
        HttpEntity.Chunked(
          request.entity.contentType,
          data.map {
            case chunk: Chunk => chunk
            case LastChunk(_, trailers) =>
              // Gets encoded like an HTTP/1.1 header, with a leading byte that indicates this an uncompressed trailer
              val trailerBytes = trailers.foldLeft(ByteString.empty) { (bytes, trailer) =>
                bytes ++ ByteString(trailer.lowercaseName() + ": " + trailer.value() + "\r\n")
              }
              val length = trailerBytes.length
              // Now create the header, which is 0x80 followed by the 32 bit length
              val trailerHeader = ByteString(0x80.toByte,
                                             (length >> 24).toByte,
                                             (length >> 16).toByte,
                                             (length >> 8).toByte,
                                             length.toByte)
              Chunk(trailerHeader ++ trailerBytes)
          }
        )
      case other => other.withContentType(request.entity.contentType)
    }

    // And if requested to base64 encode, we do that
    val encoded =
      if (request.header[Accept].exists(_.mediaRanges.exists(_.value.startsWith("application/grpc-web-text")))) {
        encodeText(proto, withEncodedTrailer)
      } else withEncodedTrailer

    response.withEntity(encoded)
  }

  private def decodeText(newContentType: ContentType, entity: MessageEntity): MessageEntity =
    entity match {
      case HttpEntity.Strict(_, data) =>
        HttpEntity.Strict(newContentType, data.decodeBase64)
      case streamed =>
        HttpEntity.Chunked.fromData(newContentType, streamed.dataBytes.via(new Base64DecoderStage))
    }

  private def encodeText(proto: String, entity: ResponseEntity): ResponseEntity = {
    val contentType = ContentType
      .parse(if (proto == null) "application/grpc-web-text" else s"application/grpc-web-text+$proto")
      .right
      .get
    entity match {
      case HttpEntity.Strict(_, data) =>
        HttpEntity.Strict(contentType, data.encodeBase64)
      case streamed =>
        HttpEntity.Chunked.fromData(contentType, streamed.dataBytes.map(_.encodeBase64))
    }
  }

  private class Base64DecoderStage extends GraphStage[FlowShape[ByteString, ByteString]] {

    private val in = Inlet[ByteString]("Base64Decoder.in")
    private val out = Outlet[ByteString]("Base64Decoder.out")

    override val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private[this] final var carryOver = ByteString.empty

      setHandler(
        in,
        new InHandler {
          override final def onPush(): Unit = {
            val bytes = carryOver ++ grab(in)
            if (bytes.length < 4) {
              carryOver = bytes
              pull(in)
            } else {
              val next =
                (bytes.length % 4) match {
                  case 0 =>
                    carryOver = ByteString.empty
                    bytes
                  case leftOver =>
                    val nextSize = bytes.length - leftOver
                    carryOver = bytes.drop(nextSize)
                    bytes.take(nextSize)
                }

              push(out, next.decodeBase64)
            }
          }

          override final def onUpstreamFinish(): Unit = {
            val carry = carryOver
            if (carry.nonEmpty) {
              carryOver = ByteString.empty // Clear this out to not retain it beyond this point
              // This will fail, but we let it so we get the base64 error.
              emit(out, carry.decodeBase64, () => completeStage())
            } else {
              completeStage()
            }
          }
        }
      )
      setHandler(out, new OutHandler {
        override final def onPull(): Unit =
          pull(in)
      })
    }
  }

}
