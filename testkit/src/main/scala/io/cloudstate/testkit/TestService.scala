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

package io.cloudstate.testkit

import akka.actor.ActorSystem
import akka.grpc.ServiceDescription
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.testkit.{SocketUtil, TestKit, TestProbe}
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.google.protobuf.empty.{Empty => ScalaPbEmpty}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.entity._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Success

class TestService {
  val port: Int = SocketUtil.temporaryLocalPort()

  private val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
    akka.loglevel = ERROR
    akka.http.server {
      preview.enable-http2 = on
      idle-timeout = infinite
    }
  """))

  protected implicit val system: ActorSystem = ActorSystem("TestService", config)
  protected val probe: TestProbe = TestProbe("TestServiceProbe")
  private val entityDiscovery = new TestService.TestEntityDiscovery(this)

  protected def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    EntityDiscoveryHandler.partial(entityDiscovery)

  protected def start(): Unit =
    Await.result(Http().bindAndHandleAsync(handler = handler, interface = "localhost", port = port), 10.seconds)

  def expectDiscovery(): TestService.Discovery = probe.expectMsgType[TestService.Discovery]

  def terminate(): Unit = TestKit.shutdownActorSystem(system)
}

object TestService {
  val info: ServiceInfo = ServiceInfo(supportLibraryName = "Cloudstate TestKit")

  def entitySpec(entityType: String, service: ServiceDescription): EntitySpec = {
    import scala.jdk.CollectionConverters._
    entitySpec(entityType, service.descriptor.getServices.asScala.toSeq)
  }

  def entitySpec(entityType: String, descriptors: Seq[ServiceDescriptor]): EntitySpec = {
    import scala.jdk.CollectionConverters._
    def allDescriptors(descriptors: Seq[FileDescriptor]): Seq[FileDescriptor] = descriptors.flatMap { d =>
      d +: allDescriptors(d.getDependencies.asScala.toSeq ++ d.getPublicDependencies.asScala)
    }
    val proto = {
      val descriptorSet = DescriptorProtos.FileDescriptorSet.newBuilder()
      allDescriptors(descriptors.map(_.getFile)).distinctBy(_.getName).foreach(fd => descriptorSet.addFile(fd.toProto))
      descriptorSet.build().toByteString
    }
    val entities = descriptors.map(d => Entity(entityType, d.getFullName, d.getName))
    EntitySpec(proto, entities, Some(info))
  }

  final class TestEntityDiscovery(service: TestService) extends EntityDiscovery {
    private val discovery = new Discovery(service)

    override def discover(info: ProxyInfo): Future[EntitySpec] = {
      service.probe.ref ! discovery
      discovery.info(info)
      discovery.spec
    }

    override def reportError(error: UserFunctionError): Future[ScalaPbEmpty] = {
      discovery.error(error)
      Future.successful(ScalaPbEmpty.defaultInstance)
    }
  }

  final class Discovery(service: TestService) {
    private val probe = TestProbe("DiscoveryInProbe")(service.system)
    private val entitySpec = Promise[EntitySpec]()

    private[testkit] def info(info: ProxyInfo): Unit = probe.ref ! info

    private[testkit] def spec: Future[EntitySpec] = entitySpec.future

    private[testkit] def error(error: UserFunctionError): Unit = probe.ref ! error

    def expect(info: ProxyInfo): Unit = probe.expectMsg(info)

    def send(spec: EntitySpec): Unit = entitySpec.complete(Success(spec))

    def expectError(error: UserFunctionError): Unit = probe.expectMsg(error)
  }
}
