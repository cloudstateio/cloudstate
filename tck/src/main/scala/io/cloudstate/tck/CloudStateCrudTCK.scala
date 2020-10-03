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

package io.cloudstate.tck

import akka.actor.ActorSystem
import akka.grpc.ServiceDescription
import akka.testkit.TestKit
import com.example.crud.shoppingcart.shoppingcart.{ShoppingCart, ShoppingCartClient}
import com.google.protobuf.DescriptorProtos
import com.typesafe.config.ConfigFactory
import io.cloudstate.protocol.action.ActionProtocol
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.crud.Crud
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.tck.model.crud.crud.{CrudTckModel, CrudTwo}
import io.cloudstate.testkit.InterceptService.InterceptorSettings
import io.cloudstate.testkit.{InterceptService, TestClient, TestProtocol}
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class ConfiguredCloudStateCrudTCK extends CloudStateCrudTCK(CloudStateTCK.Settings.fromConfig(ConfigFactory.load()))

class CloudStateCrudTCK(description: String, settings: CloudStateTCK.Settings)
    extends WordSpec
    with MustMatchers
    with BeforeAndAfterAll
    with ScalaFutures {

  def this(settings: CloudStateTCK.Settings) = this("", settings)

  private[this] final val system = ActorSystem("CloudStateCrudTCK", ConfigFactory.load("tck"))

  private[this] final val client = TestClient(settings.proxy.host, settings.proxy.port)
  private[this] final val shoppingCartClient = ShoppingCartClient(client.settings)(system)

  private[this] final val protocol = TestProtocol(settings.service.host, settings.service.port)

  @volatile private[this] final var interceptor: InterceptService = _
  @volatile private[this] final var enabledServices = Seq.empty[String]

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 3.seconds, interval = 100.millis)

  override def beforeAll(): Unit =
    interceptor = new InterceptService(InterceptorSettings(bind = settings.tck, intercept = settings.service))

  override def afterAll(): Unit =
    try shoppingCartClient.close().futureValue
    finally try client.terminate()
    finally try protocol.terminate()
    finally interceptor.terminate()

  def expectProxyOnline(): Unit =
    TestKit.awaitCond(client.http.probe(), max = 10.seconds)

  def testFor(services: ServiceDescription*)(test: => Any): Unit = {
    val enabled = services.map(_.name).forall(enabledServices.contains)
    if (enabled) test else pending
  }

  ("Cloudstate Crud TCK " + description) when {
    "verifying discovery protocol" must {
      "verify proxy info and entity discovery" in {
        import scala.jdk.CollectionConverters._

        expectProxyOnline()

        val discovery = interceptor.expectEntityDiscovery()

        val info = discovery.expectProxyInfo()

        info.protocolMajorVersion mustBe 0
        info.protocolMinorVersion mustBe 2

        info.supportedEntityTypes must contain theSameElementsAs Seq(
          EventSourced.name,
          Crdt.name,
          Crud.name,
          ActionProtocol.name
        )

        val spec = discovery.expectEntitySpec()

        val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
        val serviceNames = descriptorSet.getFileList.asScala.flatMap(_.getServiceList.asScala.map(_.getName))

        serviceNames.size mustBe spec.entities.size

        spec.entities.find(_.serviceName == CrudTckModel.name).foreach { entity =>
          serviceNames must contain("CrudTckModel")
          entity.entityType mustBe Crud.name
          entity.persistenceId mustBe "crud-tck-model"
        }

        spec.entities.find(_.serviceName == CrudTwo.name).foreach { entity =>
          serviceNames must contain("CrudTwo")
          entity.entityType mustBe Crud.name
        }

        spec.entities.find(_.serviceName == ShoppingCart.name).foreach { entity =>
          serviceNames must contain("ShoppingCart")
          entity.entityType mustBe Crud.name
          entity.persistenceId must not be empty
        }

        enabledServices = spec.entities.map(_.serviceName)
      }
    }
  }
}
