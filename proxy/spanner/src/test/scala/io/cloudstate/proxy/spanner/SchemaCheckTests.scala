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

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.grpc.GrpcClientSettings
import akka.NotUsed
import com.google.longrunning.OperationsClient
import com.google.spanner.admin.database.v1.DatabaseAdminClient
import org.slf4j.LoggerFactory
import scala.concurrent.duration.DurationInt
import scala.concurrent.Await

//final class SchemaCheckTests extends AsyncWordSpec with BeforeAndAfterAll {
//  import SchemaCheck._
//
//  private val testKit = ActorTestKit(ConfigFactory.load())
//  import testKit.system
//
//  private val clientSettings = GrpcClientSettings.fromConfig("spanner-client")
//  private val adminClient = DatabaseAdminClient(clientSettings)
//  private val operationsClient = OperationsClient(clientSettings)
//
//  "tryCreateSchema" should {
//    "succeed" in {
//      val ddl = Schema.ddl("journal", "tags", "deletions", "snapshots")
//      val database = "projects/test-project/instances/test-instance/databases/test-database"
//      tryCreateSchema(database, ddl, 1.second, 10.seconds, adminClient, operationsClient, system.scheduler)(
//        system.executionContext
//      ).map(_ => succeed)
//    }
//  }
//
//  override def afterAll(): Unit = {
//    Await.ready(adminClient.close().zip(operationsClient.close()), 42.seconds)
//    testKit.shutdownTestKit()
//  }
//}

/**
 * These tests should be run against Spanner Emulator to verify that the schema creation works.
 * Spanner Emulator needs to be set up like this:
 *
 * ```
 * gcloud beta emulators spanner start
 * gcloud spanner instances create test-instance --config=emulator-config --description="Test Instance" --nodes=1
 * gcloud spanner databases create test-database --instance test-instance
 * ```
 */
object SchemaCheckTests {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[NotUsed] = ActorSystem(Behaviors.empty[NotUsed], "system")

    val clientSettings = GrpcClientSettings.fromConfig("spanner-client")
    val adminClient = DatabaseAdminClient(clientSettings)
    val operationsClient = OperationsClient(clientSettings)

    val ddl = Schema.ddl("journal", "tags", "deletions", "snapshots")
    val database = "projects/test-project/instances/test-instance/databases/test-database"

    import system.executionContext
    val done =
      SchemaCheck
        .tryCreateSchema(database, ddl, 1.second, 10.seconds, adminClient, operationsClient, system.scheduler)
        .andThen { case result => println(result) }
        .andThen { case _ => system.terminate() }

    Await.ready(done, 42.seconds)
  }
}
