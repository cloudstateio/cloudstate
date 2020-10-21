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

import java.sql.Connection

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.util.Timeout
import io.cloudstate.proxy.valueentity.store.{
  JdbcSlickDatabase,
  JdbcValueEntityTable,
  JdbcValueEntityTableConfiguration
}
import slick.jdbc.{H2Profile, JdbcProfile, MySQLProfile, PostgresProfile}
import slick.jdbc.meta.MTable

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SlickEnsureValueEntityTablesExistReadyCheck(system: ActorSystem) extends (() => Future[Boolean]) {

  private val valueEntityConfig = system.settings.config.getConfig("cloudstate.proxy")
  private val autoCreateTables = valueEntityConfig.getBoolean("jdbc.auto-create-tables")

  private val check: () => Future[Boolean] = if (autoCreateTables) {
    // Get a hold of the cloudstate.proxy.value-entity-persistence-store.jdbc.database.slick database instance
    val db = JdbcSlickDatabase(valueEntityConfig)

    val actor = system.actorOf(
      BackoffSupervisor.props(
        BackoffOpts.onFailure(
          childProps = Props(new EnsureValueEntityTablesExistsActor(db)),
          childName = "value-entity-table-creator",
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ),
      "value-entity-table-creator-supervisor"
    )

    implicit val timeout = Timeout(10.seconds) // TODO make configurable?
    import akka.pattern.ask

    () => (actor ? EnsureValueEntityTablesExistsActor.Ready).mapTo[Boolean]
  } else { () =>
    Future.successful(true)
  }

  override def apply(): Future[Boolean] = check()
}

private object EnsureValueEntityTablesExistsActor {

  case object Ready

}

/**
 * Copied/adapted from https://github.com/lagom/lagom/blob/60897ef752ddbfc28553d3726b8fdb830a3ebdc4/persistence-jdbc/core/src/main/scala/com/lightbend/lagom/internal/persistence/jdbc/SlickProvider.scala
 */
private class EnsureValueEntityTablesExistsActor(db: JdbcSlickDatabase) extends Actor with ActorLogging {
  // TODO refactor this to be in sync with the event sourced one

  import EnsureValueEntityTablesExistsActor._

  private val profile = db.profile

  import profile.api._

  implicit val ec = context.dispatcher

  private val stateCfg = new JdbcValueEntityTableConfiguration(
    context.system.settings.config.getConfig("cloudstate.proxy.value-entity-persistence-store.jdbc-state-store")
  )

  private val stateTable = new JdbcValueEntityTable {
    override val valueEntityTableCfg: JdbcValueEntityTableConfiguration = stateCfg
    override val profile: JdbcProfile = EnsureValueEntityTablesExistsActor.this.profile
  }

  private val stateStatements = stateTable.ValueEntityTableQuery.schema.createStatements.toSeq

  import akka.pattern.pipe

  db.database.run {
    for {
      _ <- createTable(stateStatements, tableExists(stateCfg.schemaName, stateCfg.tableName))
    } yield Done.getInstance()
  } pipeTo self

  override def receive: Receive = {
    case Done => context become done
    case Status.Failure(ex) => throw ex
    case Ready => sender() ! false
  }

  private def done: Receive = {
    case Ready => sender() ! true
  }

  private def createTable(schemaStatements: Seq[String], tableExists: (Vector[MTable], Option[String]) => Boolean) =
    for {
      currentSchema <- getCurrentSchema
      tables <- getTables(currentSchema)
      _ <- createTableInternal(tables, currentSchema, schemaStatements, tableExists)
    } yield Done.getInstance()

  private def createTableInternal(
      tables: Vector[MTable],
      currentSchema: Option[String],
      schemaStatements: Seq[String],
      tableExists: (Vector[MTable], Option[String]) => Boolean
  ) =
    if (tableExists(tables, currentSchema)) {
      DBIO.successful(())
    } else {
      if (log.isDebugEnabled) {
        log.debug("Creating table, executing: " + schemaStatements.mkString("; "))
      }

      DBIO
        .sequence(schemaStatements.map { s =>
          SimpleDBIO { ctx =>
            val stmt = ctx.connection.createStatement()
            try {
              stmt.executeUpdate(s)
            } finally {
              stmt.close()
            }
          }
        })
        .asTry
        .flatMap {
          case Success(_) => DBIO.successful(())
          case Failure(f) =>
            getTables(currentSchema).map { tables =>
              if (tableExists(tables, currentSchema)) {
                log.debug("Table creation failed, but table existed after it was created, ignoring failure", f)
                ()
              } else {
                throw f
              }
            }
        }
    }

  private def getTables(currentSchema: Option[String]) =
    // Calling MTable.getTables without parameters fails on MySQL
    // See https://github.com/lagom/lagom/issues/446
    // and https://github.com/slick/slick/issues/1692
    profile match {
      case _: MySQLProfile =>
        MTable.getTables(currentSchema, None, Option("%"), None)
      case _ =>
        MTable.getTables(None, currentSchema, Option("%"), None)
    }

  private def getCurrentSchema: DBIO[Option[String]] =
    SimpleDBIO(ctx => tryGetSchema(ctx.connection).getOrElse(null)).flatMap { schema =>
      if (schema == null) {
        // Not all JDBC drivers support the getSchema method:
        // some always return null.
        // In that case, fall back to vendor-specific queries.
        profile match {
          case _: H2Profile =>
            sql"SELECT SCHEMA();".as[String].headOption
          case _: MySQLProfile =>
            sql"SELECT DATABASE();".as[String].headOption
          case _: PostgresProfile =>
            sql"SELECT current_schema();".as[String].headOption
          case _ =>
            DBIO.successful(None)
        }
      } else DBIO.successful(Some(schema))
    }

  // Some older JDBC drivers don't implement Connection.getSchema
  // (including some builds of H2). This causes them to throw an
  // AbstractMethodError at runtime.
  // Because Try$.apply only catches NonFatal errors, and AbstractMethodError
  // is considered fatal, we need to construct the Try explicitly.
  private def tryGetSchema(connection: Connection): Try[String] =
    try Success(connection.getSchema)
    catch {
      case e: AbstractMethodError =>
        Failure(new IllegalStateException("Database driver does not support Connection.getSchema", e))
    }

  private def tableExists(
      schemaName: Option[String],
      tableName: String
  )(tables: Vector[MTable], currentSchema: Option[String]): Boolean =
    tables.exists { t =>
      profile match {
        case _: MySQLProfile =>
          t.name.catalog.orElse(currentSchema) == schemaName.orElse(currentSchema) && t.name.name == tableName
        case _ =>
          t.name.schema.orElse(currentSchema) == schemaName.orElse(currentSchema) && t.name.name == tableName
      }
    }

}
