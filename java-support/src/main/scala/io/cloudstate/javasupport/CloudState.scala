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

import java.util.Objects.requireNonNull
import java.util.concurrent.CompletionStage

import com.typesafe.config.{Config, ConfigFactory}
import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ActorMaterializer, Materializer}
import io.cloudstate.javasupport.impl.{CrdtImpl, EntityDiscoveryImpl, EventSourcedImpl, AnySupport}
import io.cloudstate.protocol.entity.{Entity, EntityDiscoveryHandler}
import io.cloudstate.protocol.event_sourced.EventSourcedHandler
import io.cloudstate.protocol.crdt.CrdtHandler
import io.cloudstate.protocol.function.StatelessFunctionHandler

import scala.compat.java8.FutureConverters
import scala.concurrent.Future

object CloudState {
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
final class CloudState private[this](_system: ActorSystem, _service: StatefulService) {
  final val service = requireNonNull(_service, "StatefulService must not be null!")
  private[this] implicit final val system = _system
  private[this] implicit final val materializer: Materializer = ActorMaterializer()

  private[this] final val configuration = new CloudState.Configuration(system.settings.config.getConfig("cloudstate"))

  def this(_service: StatefulService) {
    this(
      {
        val conf = ConfigFactory.load()
        // We do this to apply the cloud-state specific akka configuration to the ActorSystem we create for hosting the user function
        ActorSystem("StatefulService", conf.getConfig("cloudstate.system").withFallback(conf))
      }, 
      _service
    )
  }

  private[this] def createRoutes(): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val eventSourceImpl     = new EventSourcedImpl(system, service)
    val entityDiscoveryImpl = new EntityDiscoveryImpl(system, service)
    val crdtImpl            = new CrdtImpl(system, service)

    CrdtHandler.partial(crdtImpl) orElse
    EventSourcedHandler.partial(eventSourceImpl) orElse
    EntityDiscoveryHandler.partial(entityDiscoveryImpl) orElse
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
abstract class StatefulService {
  import com.google.protobuf.DescriptorProtos.FileDescriptorSet
  // FIXME add all User Function configuration to this class
  def descriptors: FileDescriptorSet = FileDescriptorSet.getDefaultInstance // FIXME have this provided
  def entities: Seq[Entity] = Nil // FIXME have this provided
  final val anySupport = new AnySupport(Nil, getClass.getClassLoader) // FIXME implement. use the ActorSystem's DynamicAccess.classLoader?
}