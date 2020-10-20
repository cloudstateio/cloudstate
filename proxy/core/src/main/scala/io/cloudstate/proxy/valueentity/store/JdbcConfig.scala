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

package io.cloudstate.proxy.valueentity.store

import com.typesafe.config.Config
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.{JdbcBackend, JdbcProfile}

class JdbcValueEntityTableColumnNames(config: Config) {
  private val cfg = config.getConfig("tables.state.columnNames")

  val persistentId: String = cfg.getString("persistentId")
  val entityId: String = cfg.getString("entityId")
  val state: String = cfg.getString("state")

  override def toString: String = s"JdbcCrudStateTableColumnNames($persistentId,$entityId,$state)"
}

class JdbcValueEntityTableConfiguration(config: Config) {
  private val cfg = config.getConfig("tables.state")

  val tableName: String = cfg.getString("tableName")
  val schemaName: Option[String] = cfg.getString("schemaName") match {
    case "" => None
    case schema => Some(schema.trim)
  }
  val columnNames: JdbcValueEntityTableColumnNames = new JdbcValueEntityTableColumnNames(config)

  override def toString: String = s"JdbcCrudStateTableConfiguration($tableName,$schemaName,$columnNames)"
}

object JdbcSlickDatabase {

  def apply(config: Config): JdbcSlickDatabase = {
    val database: JdbcBackend.Database = Database.forConfig(
      "crud.jdbc.database.slick",
      config
    )
    val profile: JdbcProfile = DatabaseConfig
      .forConfig[JdbcProfile](
        "crud.jdbc.database.slick",
        config
      )
      .profile

    JdbcSlickDatabase(database, profile)
  }

}

case class JdbcSlickDatabase(database: JdbcBackend.Database, profile: JdbcProfile)
