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

package io.cloudstate.operator.stores
import io.cloudstate.operator.StatefulStore.Resource
import io.cloudstate.operator.{Condition, ImageConfig, OperatorConstants, StatefulStore, Validated}
import play.api.libs.json.JsValue
import skuber.EnvVar
import skuber.api.client.KubernetesClient

object InMemoryStoreSupport
    extends StatefulStoreSupport
    with ConfiguredStatefulStore
    with StatefulStoreUsageConfiguration {
  override def name: String = OperatorConstants.InMemoryStatefulStoreType
  override def validate(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    Validated(this)
  override def reconcile(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    Validated(this)
  override def successfulConditions: List[Condition] = List()
  override def validateInstance(config: Option[JsValue],
                                client: KubernetesClient): Validated[StatefulStoreUsageConfiguration] = Validated(this)
  override def proxyImage(config: ImageConfig): String = config.inMemory
  override def proxyContainerEnvVars: List[EnvVar] = Nil
}
