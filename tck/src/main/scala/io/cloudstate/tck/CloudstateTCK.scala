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

class ConfiguredCloudstateTCK extends CloudstateTCK(TCKSpec.Settings.loadFromConfig())

class CloudstateTCK(description: String, val settings: TCKSpec.Settings)
    extends TCKSpec
    with ProxyTCK
    with ActionTCK
    with EntityTCK
    with EventSourcedEntityTCK
    with CrdtEntityTCK
    with EventingTCK {

  def this(settings: TCKSpec.Settings) = this("", settings)

  ("Cloudstate TCK " + description) when {

    "verifying discovery protocol" must verifyDiscovery()

    "verifying model test: action" must verifyActionModel()

    "verifying model test: entity" must verifyEntityModel()

    "verifying model test: event sourced entity" must verifyEventSourcedEntityModel()

    "verifying model test: CRDT entity" must verifyCrdtEntityModel()

    "verifying proxy test: action" must verifyActionProxy()

    "verifying proxy test: entity" must verifyEntityProxy()

    "verifying proxy test: event sourced entity" must verifyEventSourcedEntityProxy()

    "verifying proxy test: CRDT entity" must verifyCrdtEntityProxy()

    "verifying proxy test: gRPC server reflection" must verifyServerReflection()

    "verifying proxy test: event log subscriptions" must verifyEventingProxy()

  }
}
