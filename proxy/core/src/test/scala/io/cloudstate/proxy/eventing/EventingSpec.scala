package io.cloudstate.proxy.eventing

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

import akka.actor.ActorRef

import scala.concurrent.duration._
import akka.{ConfigurationException, Done, NotUsed}
import akka.actor.ActorSystem
import akka.util.Timeout
import akka.stream.scaladsl.Flow
import org.scalatest._
import akka.testkit.TestProbe
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.google.protobuf.empty.Empty
import io.cloudstate.proxy.CloudStateProxyMain
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}

import scala.concurrent.Future

class EventingSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  final var system: ActorSystem = _
  override protected def beforeAll(): Unit = system = CloudStateProxyMain.start()
  override protected def afterAll(): Unit = {
    system.terminate()
    system = null
  }

  final def testEventing: Boolean = system.settings.config.getString("cloudstate.proxyeventing") != "none"

  "Eventing API" must {
    "someday work" in {
      assume(testEventing)
    }
  }
}
