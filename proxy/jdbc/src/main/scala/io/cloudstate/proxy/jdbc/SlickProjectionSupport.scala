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

package io.cloudstate.proxy.jdbc

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.projection.{ProjectionContext, ProjectionId}
import akka.projection.scaladsl.{AtLeastOnceFlowProjection, SourceProvider}
import akka.projection.slick.SlickProjection
import akka.stream.scaladsl.FlowWithContext
import io.cloudstate.proxy.eventing.ProjectionSupport
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

class SlickProjectionSupport(implicit system: ActorSystem[_]) extends ProjectionSupport {
  import system.executionContext

  private lazy val databaseConfig = {
    val dbConfig =
      DatabaseConfig.forConfig[JdbcProfile]("akka-persistence-jdbc.shared-databases.slick", system.settings.config)
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
                                        "shutdown-slick-projection-factory-connection-pool") { () =>
      dbConfig.db.shutdown.map(_ => Done)
    }
    dbConfig
  }

  override def create[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      flow: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _]
  ): AtLeastOnceFlowProjection[Offset, Envelope] =
    SlickProjection.atLeastOnceFlow(projectionId, sourceProvider, databaseConfig, flow)

  override def prepare(): Future[Done] =
    SlickProjection.createOffsetTableIfNotExists(databaseConfig)
}
