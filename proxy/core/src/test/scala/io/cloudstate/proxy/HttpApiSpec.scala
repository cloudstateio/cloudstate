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

import akka.ConfigurationException
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest._
import io.cloudstate.proxy.test._
import com.google.protobuf.Descriptors.FileDescriptor
import io.cloudstate.proxy.PathTemplateParser.PathTemplateParseException

class HttpApiSpec extends WordSpec with MustMatchers with ScalatestRouteTest {

  def assertConfigurationFailure(d: FileDescriptor, n: String, msg: String): Assertion =
    intercept[ConfigurationException] {
      val service = d.findServiceByName(n)
      service must not be (null)
      HttpApi.serve(List(service -> PartialFunction.empty))
    }.getMessage must equal(msg)

  def assertPathTemplateParseFailure(d: FileDescriptor, n: String, msg: String): Assertion =
    intercept[PathTemplateParseException] {
      val service = d.findServiceByName(n)
      service must not be (null)
      HttpApi.serve(List(service -> PartialFunction.empty))
    }.getMessage must equal(msg)

  "HTTP API" must {
    "not allow empty patterns" in {
      assertConfigurationFailure(
        IllegalHttpConfig0.IllegalHttpConfig0Proto.javaDescriptor,
        "IllegalHttpConfig0",
        "HTTP API Config: Pattern missing for rule [HttpRule(,Empty,,,Vector(),UnknownFieldSet(Map()))]!"
      )
    }

    "not allow selectors which do not exist as service methods" in {
      assertConfigurationFailure(
        IllegalHttpConfig1.IllegalHttpConfig1Proto.javaDescriptor,
        "IllegalHttpConfig1",
        "HTTP API Config: Rule selector [wrongSelector] must be empty or [cloudstate.proxy.test.IllegalHttpConfig1.fail]"
      )
    }

    "not allow patterns which do not start with slash" in {
      assertPathTemplateParseFailure(
        IllegalHttpConfig2.IllegalHttpConfig2Proto.javaDescriptor,
        "IllegalHttpConfig2",
        "Template must start with a slash at character 1 of 'no/initial/slash'"
      )
    }

    "not allow path extractors which refer to repeated fields" in {
      assertConfigurationFailure(
        IllegalHttpConfig3.IllegalHttpConfig3Proto.javaDescriptor,
        "IllegalHttpConfig3",
        "HTTP API Config: Repeated parameters [cloudstate.proxy.test.IllegalHttpConfig3Message.illegal_repeated] are not allowed as path variables"
      )
    }

    "not allow path extractors which refer to map fields" in pending

    "not allow path extractors to be duplicated in the same rule" in {
      assertPathTemplateParseFailure(
        IllegalHttpConfig4.IllegalHttpConfig4Proto.javaDescriptor,
        "IllegalHttpConfig4",
        "Duplicate path in template at character 16 of '/{duplicated}/{duplicated}'"
      )
    }

    "not allow custom non-* custom kinds" in {
      assertConfigurationFailure(
        IllegalHttpConfig5.IllegalHttpConfig5Proto.javaDescriptor,
        "IllegalHttpConfig5",
        "HTTP API Config: Only Custom patterns with [*] kind supported but [not currently supported] found!"
      )
    }

    "not allow fieldName body-selector which does not exist on request type" in {
      assertConfigurationFailure(
        IllegalHttpConfig6.IllegalHttpConfig6Proto.javaDescriptor,
        "IllegalHttpConfig6",
        "HTTP API Config: Body configured to [not-available] but that field does not exist on input type."
      )
    }

    "not allow repeated fields in body-selector" in {
      assertConfigurationFailure(
        IllegalHttpConfig7.IllegalHttpConfig7Proto.javaDescriptor,
        "IllegalHttpConfig7",
        "HTTP API Config: Body configured to [not_allowed] but that field does not exist on input type."
      )
    }

    "not allow fieldName responseBody-selector which does not exist on response type" in {
      assertConfigurationFailure(
        IllegalHttpConfig8.IllegalHttpConfig8Proto.javaDescriptor,
        "IllegalHttpConfig8",
        "HTTP API Config: Response body field [not-available] does not exist on type [google.protobuf.Empty]"
      )
    }

    "not allow more than one level of additionalBindings" in {
      assertConfigurationFailure(
        IllegalHttpConfig9.IllegalHttpConfig9Proto.javaDescriptor,
        "IllegalHttpConfig9",
        "HTTP API Config: Only one level of additionalBindings supported, but [HttpRule(,Get(/foo),,,Vector(HttpRule(,Get(/bar),,,Vector(HttpRule(,Get(/baz),,,Vector(),UnknownFieldSet(Map()))),UnknownFieldSet(Map()))),UnknownFieldSet(Map()))] has more than one!"
      )
    }

    // Not currently possible since we don't support TRACE, HEAD, CONNECT (which do not allow entity in request)
    "not allow *-body-selector on request kinds without body" in pending
    "not allow fieldName body-selector on request kinds without body" in pending
  }
}
