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

package io.cloudstate.testkit.discovery

import akka.grpc.ServiceDescription
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.testkit.TestProbe
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.google.protobuf.empty.{Empty => ScalaPbEmpty}
import io.cloudstate.protocol.entity._
import io.cloudstate.testkit.TestService.TestServiceContext
import scala.concurrent.{Future, Promise}
import scala.util.Success

final class TestEntityDiscoveryService(context: TestServiceContext) {
  import TestEntityDiscoveryService._

  private val testEntityDiscovery = new TestEntityDiscovery(context)

  def expectDiscovery(): Discovery = context.probe.expectMsgType[Discovery]

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    EntityDiscoveryHandler.partial(testEntityDiscovery)(context.system)
}

object TestEntityDiscoveryService {
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

  final class TestEntityDiscovery(context: TestServiceContext) extends EntityDiscovery {
    private val discovery = new Discovery(context)

    override def discover(info: ProxyInfo): Future[EntitySpec] = {
      context.probe.ref ! discovery
      discovery.info(info)
      discovery.spec
    }

    override def reportError(error: UserFunctionError): Future[ScalaPbEmpty] = {
      discovery.error(error)
      Future.successful(ScalaPbEmpty.defaultInstance)
    }
  }

  final class Discovery(context: TestServiceContext) {
    private val probe = TestProbe("DiscoveryInProbe")(context.system)
    private val entitySpec = Promise[EntitySpec]()

    private[testkit] def info(info: ProxyInfo): Unit = probe.ref ! info

    private[testkit] def spec: Future[EntitySpec] = entitySpec.future

    private[testkit] def error(error: UserFunctionError): Unit = probe.ref ! error

    def expect(info: ProxyInfo): Discovery = {
      probe.expectMsg(info)
      this
    }

    def send(spec: EntitySpec): Discovery = {
      entitySpec.complete(Success(spec))
      this
    }

    def expectError(error: UserFunctionError): Discovery = {
      probe.expectMsg(error)
      this
    }
  }
}
