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

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpecLike}
import slick.jdbc.JdbcProfile

class JdbcConfigSpec extends WordSpecLike with Matchers {
  private val config: Config = ConfigFactory.parseString("""
          |cloudstate.proxy {
          | value-entity-enabled = true
          | value-entity-persistence-store {
          |  store-type = "jdbc"
          |  jdbc.database.slick {
          |    profile = "slick.jdbc.PostgresProfile$"
          |    connectionPool = disabled
          |    driver = "org.postgresql.Driver"
          |    url = "jdbc:postgresql://localhost:5432/cloudstate"
          |    user = "cloudstate"
          |    password = "cloudstate"
          |  }
          |
          |  jdbc-state-store {
          |   tables {
          |     state {
          |      tableName = "value_entity_state"
          |      schemaName = ""
          |      columnNames {
          |        persistentId = "persistent_id"
          |        entityId = "entity_id"
          |        state = "state"
          |      }
          |     }
          |   }
          |  }
          | }
          |}
          |
      """.stripMargin)

  private val tableStateConfig = new JdbcValueEntityTableConfiguration(
    config.getConfig("cloudstate.proxy.value-entity-persistence-store.jdbc-state-store")
  )

  private val testTable = new JdbcValueEntityTable {
    override val valueEntityTableCfg: JdbcValueEntityTableConfiguration = tableStateConfig
    override val profile: JdbcProfile = slick.jdbc.PostgresProfile
  }

  "ValueEntityTable" should {
    def columnName(tableName: String, columnName: String) = s"$tableName.$columnName"

    "be configured with a schema name" in {
      testTable.ValueEntityTableQuery.baseTableRow.schemaName shouldBe tableStateConfig.schemaName
    }

    "be configured with a table name" in {
      testTable.ValueEntityTableQuery.baseTableRow.tableName shouldBe tableStateConfig.tableName
    }

    "be configured with a columns name" in {
      testTable.ValueEntityTableQuery.baseTableRow.persistentId.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.persistentId
      )
      testTable.ValueEntityTableQuery.baseTableRow.entityId.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.entityId
      )
      testTable.ValueEntityTableQuery.baseTableRow.state.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.state
      )
    }
  }

  "ValueEntityTableConfig" should {
    "be correctly represent as string" in {
      testTable.valueEntityTableCfg.toString shouldBe "JdbcValueEntityTableColumnNames(value_entity_state,None,JdbcValueEntityTableColumnNames(persistent_id,entity_id,state))"
    }
  }

  "JdbcSlickDatabase" should {
    "be initialized with a schema name" in {
      val slickDatabase = JdbcSlickDatabase(config.getConfig("cloudstate.proxy"))
      slickDatabase.database should not be null
      slickDatabase.profile should not be null
    }
  }
}
