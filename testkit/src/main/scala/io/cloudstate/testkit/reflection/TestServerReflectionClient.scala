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

package io.cloudstate.testkit.reflection

import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import grpc.reflection.v1alpha.reflection.ServerReflectionClient
import io.cloudstate.testkit.TestClient.TestClientContext

final class TestServerReflectionClient(context: TestClientContext) {
  private val client = ServerReflectionClient(context.clientSettings)(context.system)

  def connect(): TestServerReflectionClient.Connection = new TestServerReflectionClient.Connection(client, context)

  def terminate(): Unit = client.close()
}

object TestServerReflectionClient {
  import grpc.reflection.v1alpha.reflection.{ServerReflectionRequest => Request}
  import grpc.reflection.v1alpha.reflection.{ServerReflectionResponse => Response}

  final class Connection(client: ServerReflectionClient, context: TestClientContext) {
    import context.system

    private val in = TestPublisher.probe[Request]()
    private val out = client.serverReflectionInfo(Source.fromPublisher(in)).runWith(TestSink.probe[Response])

    out.ensureSubscription()

    def send(request: Request.MessageRequest): Connection = {
      in.sendNext(Request(context.host, request))
      this
    }

    def expect(request: Request.MessageRequest, response: Response.MessageResponse): Connection = {
      out.request(1).expectNext(Response(context.host, Some(Request(context.host, request)), response))
      this
    }

    def sendAndExpect(request: Request.MessageRequest, response: Response.MessageResponse): Connection = {
      send(request)
      expect(request, response)
      this
    }

    def close(): Unit = {
      in.sendComplete()
      out.expectComplete()
    }
  }
}
