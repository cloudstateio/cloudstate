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

package io.cloudstate.testkit.http

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.cloudstate.testkit.TestClient.TestClientContext
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

final class TestHttpClient(context: TestClientContext) {
  import context.system
  import context.system.dispatcher

  def request(path: String, body: String = null): Future[String] =
    Http()
      .singleRequest(
        HttpRequest(
          method = if (body eq null) HttpMethods.GET else HttpMethods.POST,
          uri = s"http://${context.host}:${context.port}/$path",
          entity = if (body eq null) HttpEntity.Empty else HttpEntity(ContentTypes.`application/json`, body.trim),
          protocol = HttpProtocols.`HTTP/1.1`
        )
      )
      .flatMap { response =>
        assert(response.status == StatusCodes.OK, "response status was not OK")
        assert(response.entity.contentType == ContentTypes.`application/json`, "response content type was not json")
        Unmarshal(response).to[String]
      }

  private val noRetries = ConnectionPoolSettings(context.system).withMaxRetries(0)
  private val probeRequest = HttpRequest(uri = s"http://${context.host}:${context.port}")

  def probe(): Boolean =
    Await.ready(Http().singleRequest(probeRequest, settings = noRetries), 10.seconds).value.exists(_.isSuccess)

  def terminate(): Unit =
    Await.ready(Http().shutdownAllConnectionPools(), 10.seconds)
}
