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

package io.cloudstate.proxy.crud.store

import io.cloudstate.proxy.crud.store.JdbcCrudStateTable.CrudStateRow
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import slick.lifted.{MappedProjection, ProvenShape}

object JdbcCrudStateTable {

  case class CrudStateRow(key: Key, state: String)
}

trait JdbcCrudStateTable {

  val profile: slick.jdbc.JdbcProfile

  import profile.api._

  def crudStateTableCfg: JdbcCrudStateTableConfiguration

  class CrudStateTable(tableTag: Tag)
      extends Table[CrudStateRow](_tableTag = tableTag,
                                  _schemaName = crudStateTableCfg.schemaName,
                                  _tableName = crudStateTableCfg.tableName) {
    def * : ProvenShape[CrudStateRow] = (key, state) <> (CrudStateRow.tupled, CrudStateRow.unapply)

    val persistentId: Rep[String] =
      column[String](crudStateTableCfg.columnNames.persistentId, O.Length(255, varying = true))
    val entityId: Rep[String] = column[String](crudStateTableCfg.columnNames.entityId, O.Length(255, varying = true))
    //TODO change state from Rep[String] to Rep[Array[Byte]]
    val state: Rep[String] = column[String](crudStateTableCfg.columnNames.state, O.Length(255, varying = true))
    val key: MappedProjection[Key, (String, String)] = (persistentId, entityId) <> (Key.tupled, Key.unapply)
    val pk = primaryKey(s"${tableName}_pk", (persistentId, entityId))
  }

  lazy val CrudStateTableQuery = new TableQuery(tag => new CrudStateTable(tag))
}
