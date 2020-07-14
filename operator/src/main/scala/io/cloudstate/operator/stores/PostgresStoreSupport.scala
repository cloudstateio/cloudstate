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
import io.cloudstate.operator.OperatorConstants.{StatefulStoreConditionType, UnmanagedStatefulStoreDeployment}
import io.cloudstate.operator.StatefulStore.Resource
import io.cloudstate.operator.{Condition, ImageConfig, Validated}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import skuber.EnvVar
import skuber.api.client.KubernetesClient

object PostgresStoreSupport extends StatefulStoreSupport {
  import CredentialsHelper._

  override def name: String = "Postgres"

  override def validate(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    store.spec.deployment match {
      case Some(`UnmanagedStatefulStoreDeployment`) =>
        store.spec.config.map(_.validate[PostgresConfig]) match {

          case None =>
            Validated.error(StatefulStoreConditionType,
                            "BadConfiguration",
                            s"Missing configuration for unmanaged Postgres store")

          case Some(JsError(errors)) =>
            Validated.error(
              StatefulStoreConditionType,
              "BadConfiguration",
              s"Configuration error in postgres store at ${errors.head._1}: ${errors.head._2.head.message}"
            )

          case Some(JsSuccess(config, _)) =>
            // todo validate that any referenced secrets exist
            Validated(new UnmanagedPostgres(config))

        }

      case Some(unknown) =>
        Validated.error(
          StatefulStoreConditionType,
          "UnknownDeploymentType",
          s"Unknown Postgres deployment type: $unknown, supported types for Postgres are: $UnmanagedStatefulStoreDeployment"
        )

      case None =>
        Validated.error(
          StatefulStoreConditionType,
          "UnspecifiedDeploymentType",
          s"Unspecified Postgres deployment type, supported types for Postgres are: $UnmanagedStatefulStoreDeployment"
        )

    }

  override def reconcile(store: Resource, client: KubernetesClient): Validated[ConfiguredStatefulStore] =
    validate(store, client)

  private class UnmanagedPostgres(config: PostgresConfig) extends ConfiguredStatefulStore {
    override def successfulConditions: List[Condition] = Nil

    override def validateInstance(jsConfig: Option[JsValue],
                                  client: KubernetesClient): Validated[StatefulStoreUsageConfiguration] = {
      val instanceSchema = jsConfig.flatMap(c => (c \ "schema").asOpt[String]).map(Value)
      val instanceConfig = config.copy(schema = instanceSchema.orElse(config.schema))
      Validated(new PostgresUsage(instanceConfig))
    }
  }

  private class PostgresUsage(config: PostgresConfig) extends StatefulStoreUsageConfiguration {
    override def successfulConditions: List[Condition] = Nil

    override def proxyImage(config: ImageConfig): String = config.postgres

    override def proxyContainerEnvVars: List[EnvVar] =
      List(
        EnvVar("POSTGRES_SERVICE", config.service),
        EnvVar("POSTGRES_DATABASE", config.database.toEnvVar),
        EnvVar("POSTGRES_USERNAME", config.username.toEnvVar),
        EnvVar("POSTGRES_PASSWORD", config.password.toEnvVar)
      ) ++ config.port.map(port => EnvVar("POSTGRES_PORT", port.toString)) ++
      config.schema.map(schema => EnvVar("POSTGRES_SCHEMA", schema.toEnvVar))
  }

  case class PostgresConfig(service: String,
                            port: Option[Int],
                            database: CredentialParam,
                            username: CredentialParam,
                            password: CredentialParam,
                            schema: Option[CredentialParam])

  implicit def postgresCredentialsReads: Reads[PostgresConfig] =
    ((__ \ "service").read[String] and
    (__ \ "port").readNullable[Int] and
    readCredentialParam("database") and
    readCredentialParam("username") and
    readCredentialParam("password") and
    readOptionalCredentialParam("schema"))(PostgresConfig)

}
