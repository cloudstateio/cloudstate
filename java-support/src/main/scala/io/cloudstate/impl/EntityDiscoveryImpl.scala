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

import io.cloudstate.entity._

class EntityDiscoveryImpl extends EntityDiscovery {
  
  /**
   * Discover what entities the user function wishes to serve.
   */
  def discover(in: io.cloudstate.entity.ProxyInfo): scala.concurrent.Future[io.cloudstate.entity.EntitySpec] = ???
  
  /**
   * Report an error back to the user function. This will only be invoked to tell the user function
   * that it has done something wrong, eg, violated the protocol, tried to use an entity type that
   * isn't supported, or attempted to forward to an entity that doesn't exist, etc. These messages
   * should be logged clearly for debugging purposes.
   */
  def reportError(in: io.cloudstate.entity.UserFunctionError): scala.concurrent.Future[com.google.protobuf.empty.Empty] = ???
  
}