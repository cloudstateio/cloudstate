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

import akka.actor.ActorRef

import scala.concurrent.duration._
import akka.{ConfigurationException, Done, NotUsed}
import akka.util.Timeout
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Flow
import org.scalatest._
import akka.testkit.TestProbe
import io.cloudstate.proxy.test._
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.google.protobuf.empty.Empty
import io.cloudstate.entity.{EntityDiscovery, EntityDiscoveryClient, EntitySpec, ProxyInfo, UserFunctionError}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}

import scala.concurrent.Future

class HttpApiSpec extends WordSpec with MustMatchers with ScalatestRouteTest {
  implicit val timeout = Timeout(10.seconds)
  import akka.pattern.ask

  private val mockEntityDiscovery = new EntityDiscovery {
    override def discover(in: ProxyInfo): Future[EntitySpec] = ???
    override def reportError(in: UserFunctionError): Future[Empty] = ???
  }

  def assertConfigurationFailure(d: FileDescriptor, n: String, msg: String): Assertion = {
    intercept[ConfigurationException] {
      val service = d.findServiceByName(n)
      service must not be(null)
      val probe = TestProbe().ref
      val entity = ServableEntity(service.getFullName, service, new UserFunctionTypeSupport {
        override def handler: Flow[UserFunctionCommand, UserFunctionReply, NotUsed] =
          Flow[UserFunctionCommand].mapAsync(1)(handleUnary)
        override def handleUnary(command: UserFunctionCommand): Future[UserFunctionReply] =
          (probe ? command).mapTo[UserFunctionReply]
      })
      HttpApi.serve(new UserFunctionRouter(Seq(entity), mockEntityDiscovery), Seq(entity), mockEntityDiscovery)
    }.getMessage must equal(msg)
  }

  "HTTP API" must {
    "not allow empty patterns" in {
      assertConfigurationFailure(
        IllegalHttpConfig0Proto.javaDescriptor, "IllegalHttpConfig0",
        "HTTP API Config: Pattern missing for rule [HttpRule(,,,Vector(),Empty)]!"
      )
    }

    "not allow selectors which do not exist as service methods" in {
      assertConfigurationFailure(
        IllegalHttpConfig1Proto.javaDescriptor, "IllegalHttpConfig1",
        "HTTP API Config: Rule selector [wrongSelector] must be empty or [cloudstate.proxy.test.IllegalHttpConfig1.fail]"
      )
    }

    "not allow patterns which do not start with slash" in {
      assertConfigurationFailure(
        IllegalHttpConfig2Proto.javaDescriptor, "IllegalHttpConfig2",
        "HTTP API Config: Configured pattern [no/initial/slash] does not start with slash"
      )
    }

    "not allow path extractors which refer to repeated fields" in {
      assertConfigurationFailure(
        IllegalHttpConfig3Proto.javaDescriptor, "IllegalHttpConfig3",
        "HTTP API Config: Repeated parameters [cloudstate.proxy.test.IllegalHttpConfig3Message.illegal_repeated] are not allowed as path variables"
      )
    }

    "not allow path extractors which refer to map fields" in pending

    "not allow path extractors to be duplicated in the same rule" in {
      assertConfigurationFailure(
        IllegalHttpConfig4Proto.javaDescriptor, "IllegalHttpConfig4",
        "HTTP API Config: Path parameter [duplicated] occurs more than once"
      )
    }

    "not allow custom non-* custom kinds" in {
      assertConfigurationFailure(
        IllegalHttpConfig5Proto.javaDescriptor, "IllegalHttpConfig5",
        "HTTP API Config: Only Custom patterns with [*] kind supported but [not currently supported] found!"
      )
    }

    "not allow fieldName body-selector which does not exist on request type" in {
      assertConfigurationFailure(
        IllegalHttpConfig6Proto.javaDescriptor, "IllegalHttpConfig6",
        "HTTP API Config: Body configured to [not-available] but that field does not exist on input type."
      )
    }

    "not allow repeated fields in body-selector" in {
      assertConfigurationFailure(
        IllegalHttpConfig7Proto.javaDescriptor, "IllegalHttpConfig7",
        "HTTP API Config: Body configured to [not_allowed] but that field does not exist on input type."
      )
    }

    "not allow fieldName responseBody-selector which does not exist on response type" in {
      assertConfigurationFailure(
        IllegalHttpConfig8Proto.javaDescriptor, "IllegalHttpConfig8",
        "HTTP API Config: Response body field [not-available] does not exist on type [google.protobuf.Empty]"
      )
    }

    "not allow more than one level of additionalBindings" in {
      assertConfigurationFailure(
        IllegalHttpConfig9Proto.javaDescriptor, "IllegalHttpConfig9",
        "HTTP API Config: Only one level of additionalBindings supported, but [HttpRule(,,,Vector(HttpRule(,,,Vector(HttpRule(,,,Vector(),Get(/baz))),Get(/bar))),Get(/foo))] has more than one!"
      )
    }

    // Not currently possible since we don't support TRACE, HEAD, CONNECT (which do not allow entity in request)
    "not allow *-body-selector on request kinds without body" in pending
    "not allow fieldName body-selector on request kinds without body" in pending
  }
}
