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

import akka.grpc.ServiceDescription
import akka.testkit.TestKit
import com.google.protobuf.DescriptorProtos
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.entity.{Entity, EntitySpec, ProxyInfo}
import io.cloudstate.testkit.InterceptService.InterceptorSettings
import io.cloudstate.testkit.{InterceptService, ServiceAddress, TestClient, TestProtocol}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, OptionValues, WordSpec}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object TCKSpec {
  final case class Settings(tck: ServiceAddress, proxy: ServiceAddress, service: ServiceAddress)

  object Settings {
    def loadFromConfig(): Settings = fromConfig(ConfigFactory.load())

    def fromConfig(config: Config): Settings = {
      val tckConfig = config.getConfig("cloudstate.tck")
      Settings(
        ServiceAddress(tckConfig.getString("hostname"), tckConfig.getInt("port")),
        ServiceAddress(tckConfig.getString("proxy.hostname"), tckConfig.getInt("proxy.port")),
        ServiceAddress(tckConfig.getString("service.hostname"), tckConfig.getInt("service.port"))
      )
    }
  }
}

trait TCKSpec
    extends WordSpec
    with MustMatchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with OptionValues {

  def settings: TCKSpec.Settings

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 3.seconds, interval = 100.millis)

  val client: TestClient = TestClient(settings.proxy.host, settings.proxy.port)

  val protocol: TestProtocol = TestProtocol(settings.service.host, settings.service.port)

  val interceptor: InterceptService = new InterceptService(InterceptorSettings(settings.tck, settings.service))

  @volatile private[this] var _entitySpec: EntitySpec = EntitySpec()
  @volatile private[this] var _proxyInfo: ProxyInfo = ProxyInfo()

  override def beforeAll(): Unit =
    try {
      start()
      discover()
      super.beforeAll()
    } catch {
      case error: Throwable => onStartError(error)
    }

  def onStartError(error: Throwable): Unit = throw error

  override def afterEach(): Unit = {
    super.afterEach()
    interceptor.verifyNoMoreInteractions()
  }

  override def afterAll(): Unit =
    try super.afterAll()
    finally stop()

  def start(): Unit = interceptor.start()

  def entitySpec: EntitySpec = _entitySpec

  def proxyInfo: ProxyInfo = _proxyInfo

  def discover(): Unit = {
    TestKit.awaitCond(client.http.probe(), max = 10.seconds)
    val discovery = interceptor.expectEntityDiscovery()
    _entitySpec = discovery.expectEntitySpec()
    _proxyInfo = discovery.expectProxyInfo()
  }

  def stop(): Unit =
    try client.terminate()
    finally try protocol.terminate()
    finally interceptor.terminate()

  def entity(name: String): Option[Entity] = entitySpec.entities.find(_.serviceName == name)

  lazy val serviceNames: Seq[String] = entitySpec.entities.map(_.serviceName)

  lazy val discoveredServices: Seq[String] =
    DescriptorProtos.FileDescriptorSet
      .parseFrom(entitySpec.proto)
      .getFileList
      .asScala
      .flatMap(file => file.getServiceList.asScala.map(service => s"${file.getPackage}.${service.getName}"))
      .toSeq

  def testFor(services: ServiceDescription*)(test: => Any): Unit = {
    val enabled = services.map(_.name).forall(serviceNames.contains)
    if (enabled) test else pending
  }
}
