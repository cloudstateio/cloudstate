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

package io.cloudstate

import io.cloudstate.impl.EntityDiscoveryAndSourcingImpl

import com.typesafe.config.{Config, ConfigFactory}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ServerSettings
import java.util.Objects.requireNonNull

import io.cloudstate.entity.{EntityDiscoveryHandler, EntityDiscovery}
import io.cloudstate.eventsourced.{EventSourcedHandler, EventSourced}

import scala.concurrent.Future
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters

final class CloudState private[this](_system: ActorSystem, _service: StatefulService) {
  final val service = requireNonNull(_service, "StatefulService must not be null!")
  implicit final val system = _system
  implicit final val materializer: Materializer = ActorMaterializer()

  private final val impl = new EntityDiscoveryAndSourcingImpl(system)

  def this(_service: StatefulService) {
    this(
      {
        val conf = ConfigFactory.load()
        ActorSystem("StatefulService", conf.getConfig("cloudstate.system").withFallback(conf))
      }, 
      _service
    )
  }

  private[this] def createRoutes(): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    EntityDiscoveryHandler.partial(impl) orElse
    EventSourcedHandler.partial(impl) orElse
    { case _ => Future.successful(HttpResponse(StatusCodes.NotFound)) }
  }

  def run(): CompletionStage[Done] = {
    val config = system.settings.config
    // FIXME due to some issues with the Java code generation for the protocols, this looks messy. Revisit once Java generation works as intended.
    FutureConverters.toJava(Http.get(system).bindAndHandleAsync(
        createRoutes(),
        config.getString("cloudstate.host"),
        config.getInt("cloudstate.port"),
        HttpConnectionContext(UseHttp2.Always))).thenCompose(_ => FutureConverters.toJava(system.terminate())).thenApply(_ => Done)
  }
}

abstract class StatefulService {

}