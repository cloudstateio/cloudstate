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

package com.lightbend.statefulserverless

import scala.concurrent.duration._
import akka.ConfigurationException
import akka.util.Timeout
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}
import akka.testkit.{ TestActors, TestKit, TestProbe }
import com.lightbend.statefulserverless.test._
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}

class HttpApiSpec extends WordSpec with MustMatchers with ScalatestRouteTest {
  val config = ConfigFactory.load()
  val timeout = Timeout(10.seconds)

  def assertConfigurationFailure(d: FileDescriptor, n: String, msg: String): Assertion = {
    intercept[ConfigurationException] {
      val service = d.findServiceByName(n)
      service must not be(null)
      HttpApi.serve(TestProbe().ref, timeout, service)
    }.getMessage must equal(msg)
  }

  "HTTP API" must {
    "not allow selectors which do not exist as service methods" in {
      assertConfigurationFailure(
        IllegalHttpConfig1Proto.javaDescriptor, "IllegalHttpConfig1",
        "HTTP API Config: Rule selector [wrongSelector] must be empty or [com.lightbend.statefulserverless.test.IllegalHttpConfig1.fail]"
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
        "HTTP API Config: Repeated parameters [com.lightbend.statefulserverless.test.IllegalHttpConfig3Message.illegal_repeated] are not allowed as path variables"
      )
    }
  }
}
