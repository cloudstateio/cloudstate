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

package io.cloudstate.impl

import io.cloudstate.StatefulService
import io.cloudstate.entity._

import scala.concurrent.Future

import akka.actor.ActorSystem

class EntityDiscoveryImpl(system: ActorSystem, service: StatefulService) extends EntityDiscovery {
  /**
   * Discover what entities the user function wishes to serve.
   */
  override def discover(in: io.cloudstate.entity.ProxyInfo): scala.concurrent.Future[io.cloudstate.entity.EntitySpec] = {
    system.log.info(s"Received discovery call from sidecar [${in.proxyName} ${in.proxyVersion}] supporting CloudState ${in.protocolMajorVersion}.${in.protocolMinorVersion}")
    system.log.debug(s"Supported sidecar entity types: ${in.supportedEntityTypes.mkString("[",",","]")}")

    if ( false )/* TODO verify compatibility with in.protocolMajorVersion & in.protocolMinorVersion */
      Future.failed(new Exception("Proxy version not compatible with library protocol support version")) // TODO how to handle if we have entity types not supported by the proxy?
    else {
       Future.successful(EntitySpec(service.descriptors.toByteString, service.entities))
    }
  }
  
  /**
   * Report an error back to the user function. This will only be invoked to tell the user function
   * that it has done something wrong, eg, violated the protocol, tried to use an entity type that
   * isn't supported, or attempted to forward to an entity that doesn't exist, etc. These messages
   * should be logged clearly for debugging purposes.
   */
  override def reportError(in: io.cloudstate.entity.UserFunctionError): scala.concurrent.Future[com.google.protobuf.empty.Empty] = {
    system.log.error(s"Error reported from sidecar: ${in.message}")
    Future.successful(com.google.protobuf.empty.Empty()) // TODO Cache instance
  }
}