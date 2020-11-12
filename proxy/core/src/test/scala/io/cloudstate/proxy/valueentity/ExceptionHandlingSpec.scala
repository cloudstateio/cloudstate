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

package io.cloudstate.proxy.valueentity

import akka.Done
import akka.actor.ActorSystem
import akka.grpc.{GrpcClientSettings, GrpcServiceException}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKit
import io.cloudstate.proxy.TestProxy
import io.cloudstate.proxy.test.thing.{Key, Thing, ThingClient}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.valueentity.{TestValueEntityService, ValueEntityMessages}
import io.grpc.{Status, StatusRuntimeException}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

class ExceptionHandlingSpec extends WordSpec with Matchers with BeforeAndAfterAll with ScalaFutures {

  import ValueEntityMessages._

  private implicit val system = ActorSystem("ValueEntityExceptionHandlingSpec")

  private val service = TestService()
  private val proxy = TestProxy(service.port)
  private val client = ThingClient(GrpcClientSettings.connectToServiceAt("localhost", proxy.port).withTls(false))
  private val spec = TestValueEntityService.entitySpec(Thing)

  private val discovery = service.entityDiscovery.expectDiscovery()
  discovery.expect(proxy.info)
  discovery.send(spec)
  proxy.expectOnline()

  override def afterAll(): Unit = {
    client.close().futureValue shouldBe Done
    TestKit.shutdownActorSystem(system)
    proxy.terminate()
    service.terminate()
  }

  "Cloudstate proxy for Value Entity" should {

    "respond with gRPC error for action failure in entity" in {
      val call = client.get(Key("one"))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "one"))
      connection.expect(command(1, "one", "Get", Key("one")))
      proxy.expectLogError("User Function responded with a failure: description goes here") {
        connection.send(actionFailure(1, "description goes here"))
      }
      val error = call.failed.futureValue
      error shouldBe a[StatusRuntimeException]
      error.getMessage shouldBe "UNKNOWN: description goes here"
      connection.close()
    }

    "respond with gRPC error for unexpected failure in entity" in {
      val call = client.get(Key("two"))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "two"))
      connection.expect(command(1, "two", "Get", Key("two")))
      proxy.expectLogError("User Function responded with a failure: Unexpected Value entity failure") {
        proxy.expectLogError("Unexpected Value entity failure - boom plus details") {
          connection.send(failure(1, "boom plus details"))
          connection.expectClosed()
        }
      }
      val error = call.failed.futureValue
      error shouldBe a[StatusRuntimeException]
      error.getMessage shouldBe "UNKNOWN: Unexpected Value entity failure"
    }

    "respond with gRPC error for stream error in entity" in {
      val call = client.get(Key("three"))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "three"))
      connection.expect(command(1, "three", "Get", Key("three")))
      proxy.expectLogError("User Function responded with a failure: Unexpected Value entity termination") {
        proxy.expectLogError("INTERNAL: stream failed") {
          connection.sendError(new GrpcServiceException(Status.INTERNAL.withDescription("stream failed")))
        }
      }
      val error = call.failed.futureValue
      error shouldBe a[StatusRuntimeException]
      error.getMessage shouldBe "UNKNOWN: Unexpected Value entity termination"
    }

    "respond with HTTP error for action failure in entity" in {
      val call = Http().singleRequest(HttpRequest(uri = Uri(s"http://localhost:${proxy.port}/thing/four")))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "four"))
      connection.expect(command(1, "four", "Get", Key("four")))
      proxy.expectLogError("User Function responded with a failure: description goes here") {
        connection.send(actionFailure(1, "description goes here"))
      }
      val response = call.futureValue
      response.status.intValue shouldBe 500
      Unmarshal(response).to[String].futureValue shouldBe "description goes here"
      connection.close()
    }

    "respond with HTTP error for unexpected failure in entity" in {
      val call = Http().singleRequest(HttpRequest(uri = Uri(s"http://localhost:${proxy.port}/thing/five")))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "five"))
      connection.expect(command(1, "five", "Get", Key("five")))
      proxy.expectLogError("User Function responded with a failure: Unexpected Value entity failure") {
        proxy.expectLogError("Unexpected Value entity failure - boom plus details") {
          connection.send(failure(1, "boom plus details"))
          connection.expectClosed()
        }
      }
      val response = call.futureValue
      response.status.intValue shouldBe 500
      Unmarshal(response).to[String].futureValue shouldBe "Unexpected Value entity failure"
    }

    "respond with HTTP error for stream error in entity" in {
      val call = Http().singleRequest(HttpRequest(uri = Uri(s"http://localhost:${proxy.port}/thing/six")))
      val connection = service.valueEntity.expectConnection()
      connection.expect(init(Thing.name, "six"))
      connection.expect(command(1, "six", "Get", Key("six")))
      proxy.expectLogError("User Function responded with a failure: Unexpected Value entity termination") {
        proxy.expectLogError("INTERNAL: stream failed") {
          connection.sendError(new GrpcServiceException(Status.INTERNAL.withDescription("stream failed")))
        }
      }
      val response = call.futureValue
      response.status.intValue shouldBe 500
      Unmarshal(response).to[String].futureValue shouldBe "Unexpected Value entity termination"
    }
  }
}
