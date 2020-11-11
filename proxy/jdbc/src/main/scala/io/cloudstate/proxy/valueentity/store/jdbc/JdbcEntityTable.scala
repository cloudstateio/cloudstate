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

import io.cloudstate.proxy.valueentity.store.Store.Key
import io.cloudstate.proxy.valueentity.store.jdbc.JdbcEntityTable.EntityRow
import slick.lifted.{MappedProjection, ProvenShape}

object JdbcEntityTable {

  case class EntityRow(key: Key, typeUrl: String, state: Array[Byte])
}

trait JdbcEntityTable {

  val profile: slick.jdbc.JdbcProfile

  import profile.api._

  def entityTableCfg: JdbcEntityTableConfiguration

  class Entity(tableTag: Tag)
      extends Table[EntityRow](_tableTag = tableTag,
                               _schemaName = entityTableCfg.schemaName,
                               _tableName = entityTableCfg.tableName) {
    def * : ProvenShape[EntityRow] = (key, typeUrl, state) <> (EntityRow.tupled, EntityRow.unapply)

    val persistentId: Rep[String] =
      column[String](entityTableCfg.columnNames.persistentId, O.Length(255, varying = true))
    val entityId: Rep[String] = column[String](entityTableCfg.columnNames.entityId, O.Length(255, varying = true))
    val typeUrl: Rep[String] = column[String](entityTableCfg.columnNames.typeUrl, O.Length(255, varying = true))
    val state: Rep[Array[Byte]] = column[Array[Byte]](entityTableCfg.columnNames.state)
    val key: MappedProjection[Key, (String, String)] = (persistentId, entityId) <> (Key.tupled, Key.unapply)
    val pk = primaryKey(s"${tableName}_pk", (persistentId, entityId))
  }

  lazy val EntityTable = new TableQuery(tag => new Entity(tag))
}
