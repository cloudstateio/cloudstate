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

package io.cloudstate.javasupport

import java.util.concurrent.CompletionStage

import com.typesafe.config.{Config, ConfigFactory}
import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import com.google.protobuf.Descriptors
import io.cloudstate.javasupport.impl.eventsourced.{EventSourcedImpl, EventSourcedStatefulService}
import io.cloudstate.javasupport.impl.EntityDiscoveryImpl
import io.cloudstate.protocol.entity.EntityDiscoveryHandler
import io.cloudstate.protocol.event_sourced.EventSourcedHandler

import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import scala.collection.JavaConverters._

object CloudStateRunner {
  final case class Configuration(userFunctionInterface: String, userFunctionPort: Int) {
    validate()
    def this(config: Config) = {
      this(
        userFunctionInterface      = config.getString("user-function-interface"),
        userFunctionPort           = config.getInt("user-function-port"),
      )
    }

    private def validate(): Unit = {
      require(userFunctionInterface.length > 0, s"user-function-interface must not be empty")
      require(userFunctionPort > 0, s"user-function-port must be greater than 0")
    }
  }
}

/**
 * Current Java API:
 * public static class UserFunction {
 *   public static void main(String[] args) {
 *      new CloudState(new StatefulService(…)).run();
 *   }
 * }
 **/
final class CloudStateRunner private[this](_system: ActorSystem, services: Map[String, StatefulService]) {
  private[this] implicit final val system = _system
  private[this] implicit final val materializer: Materializer = ActorMaterializer()

  private[this] final val configuration = new CloudStateRunner.Configuration(system.settings.config.getConfig("cloudstate"))

  def this(services: java.util.Map[String, StatefulService]) {
    this(
      {
        val conf = ConfigFactory.load()
        // We do this to apply the cloud-state specific akka configuration to the ActorSystem we create for hosting the user function
        ActorSystem("StatefulService", conf.getConfig("cloudstate.system").withFallback(conf))
      },
      services.asScala.toMap
    )
  }

  private[this] def createRoutes(): PartialFunction[HttpRequest, Future[HttpResponse]] = {

    val serviceRoutes = services.groupBy(_._2.getClass).foldLeft(PartialFunction.empty[HttpRequest, Future[HttpResponse]]) {

      case (route, (serviceClass, eventSourcedServices: Map[String, EventSourcedStatefulService]))
        if serviceClass == classOf[EventSourcedStatefulService] =>
          val eventSourcedImpl = new EventSourcedImpl(system, eventSourcedServices)
          route orElse EventSourcedHandler.partial(eventSourcedImpl)

      case (_, (serviceClass, _)) =>
        sys.error(s"Unknown StatefulService: $serviceClass")
    }

    val entityDiscovery = EntityDiscoveryHandler.partial(new EntityDiscoveryImpl(system, services))

    serviceRoutes orElse
    entityDiscovery orElse
    { case _ => Future.successful(HttpResponse(StatusCodes.NotFound)) }
  }

  def run(): CompletionStage[Done] = {
    val serverBindingFuture = Http.get(system).bindAndHandleAsync(
        createRoutes(),
        configuration.userFunctionInterface,
        configuration.userFunctionPort,
        HttpConnectionContext(UseHttp2.Always))
    FutureConverters.toJava(serverBindingFuture).thenCompose(_ => terminate())
  }

  def terminate(): CompletionStage[Done] =
    FutureConverters.toJava(system.terminate()).thenApply(_ => Done)
}

// This class will describe the stateless service and is created and passed by the user into a CloudState instance.
trait StatefulService {
  def descriptor: Descriptors.ServiceDescriptor
  def entityType: String
  def persistenceId: String = ""
}

