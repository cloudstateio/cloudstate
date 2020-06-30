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

import akka.Done
import akka.actor.ActorSystem
import io.cloudstate.operator.OperatorConstants.StatefulStoreConditionType
import io.cloudstate.operator.{Condition, ImageConfig, StatefulStore, Validated}
import play.api.libs.json.JsValue
import skuber.EnvVar
import skuber.api.client.KubernetesClient

import scala.concurrent.Future

object StatefulStoreSupport {
  private val types: List[StatefulStoreSupport] =
    List(CassandraStoreSupport, InMemoryStoreSupport, PostgresStoreSupport)

  def get(storeType: String): Option[StatefulStoreSupport] = types.find(_.name == storeType)

  def get(store: StatefulStore.Resource): Validated[StatefulStoreSupport] =
    store.spec.`type` match {
      case Some(storeType) =>
        StatefulStoreSupport.get(storeType) match {
          case Some(storeSupport) => Validated(storeSupport)
          case None =>
            Validated.error(
              StatefulStoreConditionType,
              "UnknownStoreType",
              s"Unknown store type: $storeType, supported types are: ${StatefulStoreSupport.supportedTypes.mkString(", ")}"
            )
        }
      case None =>
        Validated.error(StatefulStoreConditionType,
                        "UnspecifiedStoreType",
                        s"StatefulStore ${store.name} does not specify a store type.")
    }

  def supportedTypes: List[String] = types.map(_.name)

  object noStoreUsageConfiguration extends StatefulStoreUsageConfiguration {
    override def successfulConditions: List[Condition] = List()
    override def proxyImage(config: ImageConfig): String = config.noStore
    override def proxyContainerEnvVars: List[EnvVar] = Nil
  }

}

trait StatefulStoreSupport {
  def name: String
  def validate(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore]
  def reconcile(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore]
  def delete(spec: StatefulStore.Spec, client: KubernetesClient): Future[Done] = Future.successful(Done)
}

trait ConfiguredStatefulStore {
  def successfulConditions: List[Condition]
  def validateInstance(config: Option[JsValue], client: KubernetesClient): Validated[StatefulStoreUsageConfiguration]
}

trait StatefulStoreUsageConfiguration {
  def successfulConditions: List[Condition]
  def proxyImage(config: ImageConfig): String
  def proxyContainerEnvVars: List[EnvVar]
}
