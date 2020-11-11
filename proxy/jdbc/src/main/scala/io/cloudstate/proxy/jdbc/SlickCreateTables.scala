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
import akka.actor.ActorSystem
import akka.event.Logging
import akka.persistence.jdbc.config.{JournalTableConfiguration, SnapshotTableConfiguration}
import akka.persistence.jdbc.journal.dao.JournalTables
import akka.persistence.jdbc.snapshot.dao.SnapshotTables
import akka.persistence.jdbc.util.SlickDatabase
import io.cloudstate.proxy.jdbc.SlickCreateTables.TableConfiguration
import io.cloudstate.proxy.valueentity.store.jdbc.{JdbcEntityTable, JdbcEntityTableConfiguration, JdbcSlickDatabase}
import slick.jdbc.{H2Profile, JdbcBackend, JdbcProfile, MySQLProfile, PostgresProfile}
import slick.jdbc.meta.MTable

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object SlickCreateTables {

  case class TableConfiguration(schemaStatements: Seq[String], schemaName: Option[String], tableName: String)

}

trait SlickCreateTables {
  val system: ActorSystem
  val profile: JdbcProfile
  val database: JdbcBackend.Database
  def tableConfigurations: Seq[TableConfiguration]

  import profile.api._
  import system.dispatcher

  private final val log = Logging(system.eventStream, classOf[SlickCreateTables])

  def run(): Future[Done] =
    database.run {
      for {
        _ <- slick.dbio.DBIO.sequence(
          tableConfigurations.map(r => createTable(r.schemaStatements, tableExists(r.schemaName, r.tableName)))
        )
      } yield Done.getInstance()
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

class ValueEntitySlickCreateTable(override val system: ActorSystem, slickDb: JdbcSlickDatabase)
    extends SlickCreateTables {

  override val profile = slickDb.profile
  override val database = slickDb.database

  import profile.api._

  private val tableCfg = new JdbcEntityTableConfiguration(
    system.settings.config.getConfig("cloudstate.proxy.value-entity.persistence.jdbc")
  )

  private val table = new JdbcEntityTable {
    override val entityTableCfg: JdbcEntityTableConfiguration = tableCfg
    override val profile: JdbcProfile = ValueEntitySlickCreateTable.this.profile
  }

  private val statements = table.EntityTable.schema.createStatements.toSeq

  override val tableConfigurations: Seq[TableConfiguration] =
    Seq(TableConfiguration(statements, tableCfg.schemaName, tableCfg.tableName))
}

class EventSourcedSlickCreateTable(override val system: ActorSystem, slickDb: SlickDatabase) extends SlickCreateTables {

  override val profile = slickDb.profile
  override val database = slickDb.database

  import profile.api._

  private val journalCfg = new JournalTableConfiguration(system.settings.config.getConfig("jdbc-read-journal"))
  private val snapshotCfg = new SnapshotTableConfiguration(
    system.settings.config.getConfig("jdbc-snapshot-store")
  )

  private val journalTables = new JournalTables {
    override val journalTableCfg: JournalTableConfiguration = journalCfg
    override val profile: JdbcProfile = EventSourcedSlickCreateTable.this.profile
  }
  private val snapshotTables = new SnapshotTables {
    override val snapshotTableCfg: SnapshotTableConfiguration = snapshotCfg
    override val profile: JdbcProfile = EventSourcedSlickCreateTable.this.profile
  }

  private val journalStatements =
    profile match {
      case H2Profile =>
        // Work around https://github.com/slick/slick/issues/763
        journalTables.JournalTable.schema.createStatements
          .map(_.replace("GENERATED BY DEFAULT AS IDENTITY(START WITH 1)", "AUTO_INCREMENT"))
          .toSeq
      case MySQLProfile =>
        // Work around https://github.com/slick/slick/issues/1437
        journalTables.JournalTable.schema.createStatements
          .map(_.replace("AUTO_INCREMENT", "AUTO_INCREMENT UNIQUE"))
          .toSeq
      case _ => journalTables.JournalTable.schema.createStatements.toSeq
    }

  private val snapshotStatements = snapshotTables.SnapshotTable.schema.createStatements.toSeq

  override val tableConfigurations: Seq[TableConfiguration] = Seq(
    TableConfiguration(journalStatements, journalCfg.schemaName, journalCfg.tableName),
    TableConfiguration(snapshotStatements, snapshotCfg.schemaName, snapshotCfg.tableName)
  )
}
