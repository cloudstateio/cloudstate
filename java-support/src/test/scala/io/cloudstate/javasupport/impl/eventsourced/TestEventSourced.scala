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

package io.cloudstate.javasupport.impl.eventsourced

import akka.actor.ActorSystem
import akka.testkit.EventFilter
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.javasupport.{CloudState, CloudStateRunner}
import io.cloudstate.testkit.Sockets
import scala.reflect.ClassTag

object TestEventSourced {
  def service[T: ClassTag](descriptor: ServiceDescriptor, fileDescriptors: FileDescriptor*): TestEventSourcedService =
    new TestEventSourcedService(implicitly[ClassTag[T]].runtimeClass, descriptor, fileDescriptors)
}

class TestEventSourcedService(entityClass: Class[_],
                              descriptor: ServiceDescriptor,
                              fileDescriptors: Seq[FileDescriptor]) {
  val port: Int = Sockets.temporaryLocalPort()

  val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
    cloudstate.user-function-port = $port
    akka {
      loglevel = ERROR
      loggers = ["akka.testkit.TestEventListener"]
      http.server {
        preview.enable-http2 = on
        idle-timeout = infinite
      }
    }
  """))

  val runner: CloudStateRunner = new CloudState()
    .registerEventSourcedEntity(entityClass, descriptor, fileDescriptors: _*)
    .createRunner(config)

  runner.run()

  def expectLogError[T](message: String)(block: => T): T =
    EventFilter.error(message, occurrences = 1).intercept(block)(runner.system)

  def terminate(): Unit = runner.terminate()
}
