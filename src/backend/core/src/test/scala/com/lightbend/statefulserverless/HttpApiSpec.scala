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
import org.scalatest._
import akka.util.Timeout
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import com.typesafe.config.{Config, ConfigFactory}

class HttpApiSpec extends WordSpec with MustMatchers with ScalatestRouteTest {
  val config = ConfigFactory.load()

  // (stateManager: ActorRef, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]]
  val httpApiRoute = HttpApi.serve(???, Timeout(10.seconds), ???)

  "The HttpApi" must {
    "have tests" in pending
  }
}
