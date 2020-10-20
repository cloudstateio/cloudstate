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
import slick.jdbc.JdbcProfile

private[store] class JdbcValueEntityQueries(val profile: JdbcProfile,
                                            override val valueEntityTableCfg: JdbcValueEntityTableConfiguration)
    extends JdbcValueEntityTable {

  import profile.api._

  def selectByKey(key: Key): Query[ValueEntityTable, ValueEntityRow, Seq] =
    ValueEntityTableQuery
      .filter(_.persistentId === key.persistentId)
      .filter(_.entityId === key.entityId)
      .take(1)

  def insertOrUpdate(crudState: ValueEntityRow) = ValueEntityTableQuery.insertOrUpdate(crudState)

  def deleteByKey(key: Key) =
    ValueEntityTableQuery
      .filter(_.persistentId === key.persistentId)
      .filter(_.entityId === key.entityId)
      .delete
}
