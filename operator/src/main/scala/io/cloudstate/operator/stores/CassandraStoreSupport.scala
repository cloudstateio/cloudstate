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
import io.cloudstate.operator.{Condition, ImageConfig, OperatorConstants, StatefulStore, Validated}
import skuber.api.client.KubernetesClient
import io.cloudstate.operator.OperatorConstants._
import io.cloudstate.operator.StatefulStore.Resource
import play.api.libs.json._
import play.api.libs.functional.syntax._
import skuber.EnvVar

object CassandraStoreSupport extends StatefulStoreSupport {
  import CredentialsHelper._

  override def name: String = OperatorConstants.CassandraStatefulStoreType

  override def validate(store: StatefulStore.Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    store.spec.deployment match {
      case Some(`UnmanagedStatefulStoreDeployment`) =>
        store.spec.config.map(_.validate[CassandraConfig]) match {
          case None =>
            Validated.error(StatefulStoreConditionType,
                            "BadConfiguration",
                            s"Missing configuration for unmanaged Cassandra store")

          case Some(JsError(errors)) =>
            Validated.error(
              StatefulStoreConditionType,
              "BadConfiguration",
              s"Configuration error for Cassandra store at ${errors.head._1}: ${errors.head._2.head.message}"
            )

          case Some(JsSuccess(config, _)) =>
            // todo validate that any referenced secrets exist
            Validated(new UnmanagedCassandra(config))
        }

      case Some(unknown) =>
        Validated.error(
          StatefulStoreConditionType,
          "UnknownDeploymentType",
          s"Unknown Cassandra deployment type: $unknown, supported types for Cassandra are: $UnmanagedStatefulStoreDeployment"
        )

      case None =>
        Validated.error(
          StatefulStoreConditionType,
          "UnspecifiedDeploymentType",
          s"Unspecified Cassandra deployment type, supported types for Cassandra are: $UnmanagedStatefulStoreDeployment"
        )

    }

  override def reconcile(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    validate(store, client)

  private class UnmanagedCassandra(cassandraConfig: CassandraConfig) extends ConfiguredStatefulStore {
    override def successfulConditions: List[Condition] = Nil

    override def validateInstance(config: Option[JsValue],
                                  client: KubernetesClient): Validated[StatefulStoreUsageConfiguration] =
      config.flatMap(config => (config \ "keyspace").asOpt[String]) match {
        case Some(keyspace) =>
          Validated(new CassandraUsage(cassandraConfig, keyspace))
        case None =>
          Validated.error(StatefulStoreConditionType,
                          "MissingKeyspace",
                          "No keyspace declared for unmanaged Cassandra journal")
      }
  }

  private class CassandraUsage(config: CassandraConfig, keyspace: String) extends StatefulStoreUsageConfiguration {
    override def successfulConditions: List[Condition] = Nil
    override def proxyImage(config: ImageConfig): String = config.cassandra
    override def proxyContainerEnvVars: List[EnvVar] =
      List(
        EnvVar("CASSANDRA_CONTACT_POINTS", config.service),
        EnvVar("CASSANDRA_KEYSPACE", keyspace)
      ) ++ config.username.map(username => EnvVar("CASSANDRA_USERNAME", username.toEnvVar)) ++
      config.password.map(password => EnvVar("CASSANDRA_PASSWORD", password.toEnvVar))
  }

  case class CassandraConfig(service: String, username: Option[CredentialParam], password: Option[CredentialParam])

  implicit def cassandraCredentialsReads: Reads[CassandraConfig] =
    ((__ \ "service").read[String] and
    readOptionalCredentialParam("username") and
    readOptionalCredentialParam("password"))(CassandraConfig)
}
