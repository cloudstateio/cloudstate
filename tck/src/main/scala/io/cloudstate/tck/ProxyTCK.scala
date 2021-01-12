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

package io.cloudstate.tck

import io.cloudstate.protocol.action.ActionProtocol
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.protocol.value_entity.ValueEntity

trait ProxyTCK extends TCKSpec {

  def verifyDiscovery(): Unit =
    "verify proxy info and entity discovery" in {
      proxyInfo.protocolMajorVersion mustBe 0
      proxyInfo.protocolMinorVersion mustBe 2

      proxyInfo.supportedEntityTypes must contain theSameElementsAs Seq(
        ActionProtocol.name,
        ValueEntity.name,
        EventSourced.name,
        Crdt.name
      )

      discoveredServices must contain allElementsOf serviceNames
    }

  def verifyServerReflection(): Unit =
    "verify that the proxy supports server reflection" in {
      import grpc.reflection.v1alpha.reflection._
      import ServerReflectionRequest.{MessageRequest => In}
      import ServerReflectionResponse.{MessageResponse => Out}

      val expectedServices = Seq(ServerReflection.name) ++ serviceNames.sorted

      val connection = client.serverReflection.connect()

      connection.sendAndExpect(
        In.ListServices(""),
        Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
      )

      connection.sendAndExpect(
        In.ListServices("nonsense.blabla."),
        Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
      )

      connection.sendAndExpect(
        In.FileContainingSymbol("nonsense.blabla.Void"),
        Out.FileDescriptorResponse(FileDescriptorResponse(Nil))
      )

      connection.close()
    }
}
