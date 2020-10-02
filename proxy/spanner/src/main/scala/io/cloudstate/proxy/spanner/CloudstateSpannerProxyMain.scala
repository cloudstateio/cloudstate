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

package io.cloudstate.proxy.spanner

import akka.actor.{ActorSystem => ClassicSystem}
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.grpc.GrpcClientSettings
import com.google.auth.oauth2.GoogleCredentials
import com.google.longrunning.OperationsClient
import com.google.spanner.admin.database.v1.DatabaseAdminClient
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import io.cloudstate.proxy.CloudStateProxyMain
import io.grpc.auth.MoreCallCredentials
import scala.compat.java8.DurationConverters.DurationOps
import scala.concurrent.duration.FiniteDuration

/**
 * The main entry point for the Spanner based proxy. It starts the core proxy and then spawns
 * a [[SchemaCheck]] actor
 */
object CloudstateSpannerProxyMain {

  final object Config {

    def fromTypesafeConfig(): Config =
      fromTypesafeConfig(ConfigFactory.load().getConfig("cloudstate.proxy.spanner"))

    def fromTypesafeConfig(typesafeConfig: TypesafeConfig): Config = {
      import typesafeConfig._
      val projectId = getString("project-id")
      val instanceId = getString("instance-id")
      val databaseId = getString("database-id")
      val journalTable = getString("journal-table")
      val tagsTable = getString("tags-table")
      val deletionsTable = getString("deletions-table")
      val snapshotsTable = getString("snapshots-table")
      val operationAwaitDelay = getDuration("operation-await-delay").toScala
      val operationAwaitMaxDuration = getDuration("operation-await-max-duration").toScala
      Config(projectId,
             instanceId,
             databaseId,
             journalTable,
             tagsTable,
             deletionsTable,
             snapshotsTable,
             operationAwaitDelay,
             operationAwaitMaxDuration)
    }
  }

  final case class Config(projectId: String,
                          instanceId: String,
                          databaseId: String,
                          journalTable: String,
                          tagsTable: String,
                          deletionsTable: String,
                          snapshotsTable: String,
                          operationAwaitDelay: FiniteDuration,
                          operationAwaitMaxDuration: FiniteDuration)

  def main(args: Array[String]): Unit = {
    // Parse config early in order to fail fast
    val config = Config.fromTypesafeConfig()
    start(config)(CloudStateProxyMain.start())
  }

  def start(config: Config)(implicit classicSystem: ClassicSystem): Unit = {
    val clientSettings =
      GrpcClientSettings
        .fromConfig("spanner-client")
        .withCallCredentials(
          MoreCallCredentials.from(
            GoogleCredentials
              .getApplicationDefault()
              .createScoped(
                "https://www.googleapis.com/auth/spanner.admin",
                "https://www.googleapis.com/auth/spanner.data"
              )
          )
        )
    val adminClient = DatabaseAdminClient(clientSettings)
    val operationsClient = OperationsClient(clientSettings)

    import config._
    val databaseName = s"projects/$projectId/instances/$instanceId/databases/$databaseId"
    classicSystem.spawn(
      SchemaCheck(databaseName,
                  journalTable,
                  tagsTable,
                  deletionsTable,
                  snapshotsTable,
                  operationAwaitDelay,
                  operationAwaitMaxDuration,
                  adminClient,
                  operationsClient),
      "schema-check"
    )
  }
}
