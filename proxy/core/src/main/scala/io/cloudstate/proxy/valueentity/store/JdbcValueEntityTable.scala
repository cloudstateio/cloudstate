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

import io.cloudstate.proxy.valueentity.store.JdbcValueEntityTable.ValueEntityRow
import io.cloudstate.proxy.valueentity.store.JdbcStore.Key
import slick.lifted.{MappedProjection, ProvenShape}

object JdbcValueEntityTable {

  case class ValueEntityRow(key: Key, state: Array[Byte])
}

trait JdbcValueEntityTable {

  val profile: slick.jdbc.JdbcProfile

  import profile.api._

  def valueEntityTableCfg: JdbcValueEntityTableConfiguration

  class ValueEntityTable(tableTag: Tag)
      extends Table[ValueEntityRow](_tableTag = tableTag,
                                    _schemaName = valueEntityTableCfg.schemaName,
                                    _tableName = valueEntityTableCfg.tableName) {
    def * : ProvenShape[ValueEntityRow] = (key, state) <> (ValueEntityRow.tupled, ValueEntityRow.unapply)

    val persistentId: Rep[String] =
      column[String](valueEntityTableCfg.columnNames.persistentId, O.Length(255, varying = true))
    val entityId: Rep[String] = column[String](valueEntityTableCfg.columnNames.entityId, O.Length(255, varying = true))
    val state: Rep[Array[Byte]] = column[Array[Byte]](valueEntityTableCfg.columnNames.state)
    val key: MappedProjection[Key, (String, String)] = (persistentId, entityId) <> (Key.tupled, Key.unapply)
    val pk = primaryKey(s"${tableName}_pk", (persistentId, entityId))
  }

  lazy val ValueEntityTableQuery = new TableQuery(tag => new ValueEntityTable(tag))
}
