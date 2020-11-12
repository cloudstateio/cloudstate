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
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.persistence.jdbc.config.ConfigKeys
import akka.persistence.jdbc.util.SlickExtension
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.cloudstate.proxy.valueentity.store.jdbc.JdbcSlickDatabase

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.concurrent.Future

class SlickEnsureTablesExistReadyCheck(system: ActorSystem) extends (() => Future[Boolean]) {

  private val proxyConfig = system.settings.config.getConfig("cloudstate.proxy")
  private val autoCreateTables = proxyConfig.getBoolean("jdbc.auto-create-tables")

  // Get a hold of the akka-jdbc slick database instance
  private val eventSourcedSlickDatabase =
    SlickExtension(system).database(ConfigFactory.parseMap(Map(ConfigKeys.useSharedDb -> "slick").asJava))

  // Get a hold of the cloudstate.proxy.value-entity.persistence.jdbc.slick database instance
  private val valueEntitySlickDatabase = JdbcSlickDatabase(proxyConfig)

  private val check: () => Future[Boolean] = if (autoCreateTables) {
    tableCreateCombinations() match {
      case Nil => () => Future.successful(true)
      case combinations =>
        val actor = system.actorOf(
          BackoffSupervisor.props(
            BackoffOpts.onFailure(
              childProps = Props(new EnsureTablesExistsActor(combinations)),
              childName = "jdbc-table-creator",
              minBackoff = 3.seconds,
              maxBackoff = 30.seconds,
              randomFactor = 0.2
            )
          ),
          "jdbc-table-creator-supervisor"
        )

        implicit val timeout = Timeout(proxyConfig.getDuration("jdbc.create-tables-timeout").toMillis.millis)
        import akka.pattern.ask

        () => (actor ? EnsureTablesExistsActor.Ready).mapTo[Boolean]
    }
  } else { () =>
    Future.successful(true)
  }

  override def apply(): Future[Boolean] = check()

  private def tableCreateCombinations(): Seq[SlickCreateTables] = {
    val config = system.settings.config
    val eventSourcedEnabled = config.getBoolean("cloudstate.proxy.eventsourced-entity.journal-enabled")
    val valueEntityEnabled = config.getBoolean("cloudstate.proxy.value-entity.enabled")

    val eventSourcedCombinations =
      if (eventSourcedEnabled) Seq(new EventSourcedSlickCreateTable(system, eventSourcedSlickDatabase)) else Seq.empty
    val valueEntityCombinations =
      if (valueEntityEnabled) Seq(new ValueEntitySlickCreateTable(system, valueEntitySlickDatabase)) else Seq.empty

    eventSourcedCombinations ++ valueEntityCombinations
  }
}

private object EnsureTablesExistsActor {

  case object Ready

}

/**
 * Copied/adapted from https://github.com/lagom/lagom/blob/60897ef752ddbfc28553d3726b8fdb830a3ebdc4/persistence-jdbc/core/src/main/scala/com/lightbend/lagom/internal/persistence/jdbc/SlickProvider.scala
 */
private class EnsureTablesExistsActor(tables: Seq[SlickCreateTables]) extends Actor with ActorLogging {

  import context.dispatcher
  import akka.pattern.pipe
  import EnsureTablesExistsActor._

  Future
    .sequence(tables.map(c => c.run()))
    .map(_ => Done.getInstance())
    .pipeTo(self)

  override def receive: Receive = {
    case Done => context become done
    case Status.Failure(ex) => throw ex
    case Ready => sender() ! false
  }

  private def done: Receive = {
    case Ready => sender() ! true
  }
}
