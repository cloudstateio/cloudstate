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

package io.cloudstate.proxy.valueentity.store.jdbc

import com.typesafe.config.Config
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.{JdbcBackend, JdbcProfile}

class JdbcEntityTableColumnNames(config: Config) {
  private val cfg = config.getConfig("tables.state.columnNames")

  val persistentId: String = cfg.getString("persistentId")
  val entityId: String = cfg.getString("entityId")
  val typeUrl: String = cfg.getString("typeUrl")
  val state: String = cfg.getString("state")

  override def toString: String = s"JdbcEntityTableColumnNames($persistentId,$entityId,$typeUrl,$state)"
}

class JdbcEntityTableConfiguration(config: Config) {
  private val cfg = config.getConfig("tables.state")

  val tableName: String = cfg.getString("tableName")
  val schemaName: Option[String] = cfg.getString("schemaName") match {
    case "" => None
    case schema => Some(schema.trim)
  }
  val columnNames: JdbcEntityTableColumnNames = new JdbcEntityTableColumnNames(config)

  override def toString: String = s"JdbcEntityTableConfiguration($tableName,$schemaName,$columnNames)"
}

object JdbcSlickDatabase {

  def apply(config: Config): JdbcSlickDatabase = {
    val database: JdbcBackend.Database = Database.forConfig(
      "value-entity.persistence.jdbc.slick",
      config
    )
    val profile: JdbcProfile = DatabaseConfig
      .forConfig[JdbcProfile](
        "value-entity.persistence.jdbc.slick",
        config
      )
      .profile

    JdbcSlickDatabase(database, profile)
  }

}

case class JdbcSlickDatabase(database: JdbcBackend.Database, profile: JdbcProfile)
