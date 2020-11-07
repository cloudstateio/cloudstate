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
import slick.jdbc.JdbcProfile

private[store] class JdbcEntityQueries(val profile: JdbcProfile,
                                       override val entityTableCfg: JdbcEntityTableConfiguration)
    extends JdbcEntityTable {

  import profile.api._

  def selectByKey(key: Key): Query[Entity, EntityRow, Seq] =
    EntityTable
      .filter(_.persistentId === key.persistentId)
      .filter(_.entityId === key.entityId)
      .take(1)

  def insertOrUpdate(row: EntityRow) = EntityTable.insertOrUpdate(row)

  def deleteByKey(key: Key) =
    EntityTable
      .filter(_.persistentId === key.persistentId)
      .filter(_.entityId === key.entityId)
      .delete
}
