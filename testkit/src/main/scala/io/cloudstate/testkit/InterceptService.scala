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
import akka.grpc.GrpcClientSettings
import akka.http.scaladsl.Http
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.testkit.InterceptService.InterceptorSettings
import io.cloudstate.testkit.crdt.InterceptCrdtService
import io.cloudstate.testkit.discovery.InterceptEntityDiscovery
import io.cloudstate.testkit.eventsourced.InterceptEventSourcedService
import io.cloudstate.testkit.valueentity.InterceptValueEntityService

import scala.concurrent.Await
import scala.concurrent.duration._

final case class ServiceAddress(host: String, port: Int)

final class InterceptService(settings: InterceptorSettings) {
  import InterceptService._

  private val context = new InterceptorContext(settings.intercept.host, settings.intercept.port)
  private val entityDiscovery = new InterceptEntityDiscovery(context)
  private val crdt = new InterceptCrdtService(context)
  private val eventSourced = new InterceptEventSourcedService(context)
  private val valueBased = new InterceptValueEntityService(context)

  import context.system

  entityDiscovery.expectOnline(60.seconds)

  Await.result(
    Http().bindAndHandleAsync(
      handler = entityDiscovery.handler orElse crdt.handler orElse eventSourced.handler orElse valueBased.handler,
      interface = settings.bind.host,
      port = settings.bind.port
    ),
    10.seconds
  )

  def expectEntityDiscovery(): InterceptEntityDiscovery.Discovery = entityDiscovery.expectDiscovery()

  def expectCrdtConnection(): InterceptCrdtService.Connection = crdt.expectConnection()

  def expectEventSourcedConnection(): InterceptEventSourcedService.Connection = eventSourced.expectConnection()

  def expectValueBasedConnection(): InterceptValueEntityService.Connection = valueBased.expectConnection()

  def terminate(): Unit = {
    entityDiscovery.terminate()
    crdt.terminate()
    eventSourced.terminate()
    valueBased.terminate()
    context.terminate()
  }
}

object InterceptService {
  final case class InterceptorSettings(bind: ServiceAddress, intercept: ServiceAddress)

  object InterceptorSettings {
    def apply(bindHost: String, bindPort: Int, interceptHost: String, interceptPort: Int): InterceptorSettings =
      InterceptorSettings(ServiceAddress(bindHost, bindPort), ServiceAddress(interceptHost, interceptPort))
  }

  final class InterceptorContext(val host: String, val port: Int) {
    val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka.loglevel = ERROR
      akka.http.server {
        preview.enable-http2 = on
        idle-timeout = infinite
      }
    """))

    implicit val system: ActorSystem = ActorSystem("Interceptor", config)

    val clientSettings: GrpcClientSettings = GrpcClientSettings.connectToServiceAt(host, port).withTls(false)

    val probe: TestProbe = TestProbe("InterceptorProbe")

    def terminate(): Unit = TestKit.shutdownActorSystem(system)
  }
}
