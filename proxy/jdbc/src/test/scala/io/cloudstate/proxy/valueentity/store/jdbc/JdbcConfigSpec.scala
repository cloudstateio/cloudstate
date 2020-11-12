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

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpecLike}
import slick.jdbc.JdbcProfile

class JdbcConfigSpec extends WordSpecLike with Matchers {
  private val config: Config = ConfigFactory.parseString("""
    |cloudstate.proxy {
    |  value-entity.enabled = true
    |  value-entity.persistence {
    |    store = "jdbc"
    |    jdbc {
    |      slick {
    |        profile = "slick.jdbc.PostgresProfile$"
    |        connectionPool = disabled
    |        driver = "org.postgresql.Driver"
    |        url = "jdbc:postgresql://localhost:5432/cloudstate"
    |        user = "cloudstate"
    |        password = "cloudstate"
    |      }
    |
    |      tables {
    |        state {
    |          tableName = "value_entity_state"
    |          schemaName = ""
    |          columnNames {
    |            persistentId = "persistent_id"
    |            entityId = "entity_id"
    |            typeUrl = "type_url"
    |            state = "state"
    |          }
    |        }
    |      }
    |    }
    |  }
    |}
    |
  """.stripMargin)

  private val tableStateConfig = new JdbcEntityTableConfiguration(
    config.getConfig("cloudstate.proxy.value-entity.persistence.jdbc")
  )

  private val testTable = new JdbcEntityTable {
    override val entityTableCfg: JdbcEntityTableConfiguration = tableStateConfig
    override val profile: JdbcProfile = slick.jdbc.PostgresProfile
  }

  "EntityTable" should {
    def columnName(tableName: String, columnName: String) = s"$tableName.$columnName"

    "be configured with a schema name" in {
      testTable.EntityTable.baseTableRow.schemaName shouldBe tableStateConfig.schemaName
    }

    "be configured with a table name" in {
      testTable.EntityTable.baseTableRow.tableName shouldBe tableStateConfig.tableName
    }

    "be configured with a columns name" in {
      testTable.EntityTable.baseTableRow.persistentId.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.persistentId
      )
      testTable.EntityTable.baseTableRow.entityId.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.entityId
      )
      testTable.EntityTable.baseTableRow.typeUrl.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.typeUrl
      )
      testTable.EntityTable.baseTableRow.state.toString shouldBe columnName(
        tableStateConfig.tableName,
        tableStateConfig.columnNames.state
      )
    }
  }

  "EntityTableConfig" should {
    "be correctly represent as string" in {
      testTable.entityTableCfg.toString shouldBe
      "JdbcEntityTableConfiguration(value_entity_state,None,JdbcEntityTableColumnNames(persistent_id,entity_id,type_url,state))"
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
