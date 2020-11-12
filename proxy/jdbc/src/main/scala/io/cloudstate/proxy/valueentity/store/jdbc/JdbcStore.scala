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

import akka.actor.ActorSystem
import akka.util.ByteString
import io.cloudstate.proxy.valueentity.store.Store
import io.cloudstate.proxy.valueentity.store.Store.Key
import io.cloudstate.proxy.valueentity.store.Store.Value
import io.cloudstate.proxy.valueentity.store.jdbc.JdbcEntityTable.EntityRow

import scala.concurrent.Future

final class JdbcStore(system: ActorSystem) extends Store {

  private val config = system.settings.config.getConfig("cloudstate.proxy")
  private val slickDatabase = JdbcSlickDatabase(config)
  private val tableConfiguration = new JdbcEntityTableConfiguration(config.getConfig("value-entity.persistence.jdbc"))
  private val queries = new JdbcEntityQueries(slickDatabase.profile, tableConfiguration)
  private val db = slickDatabase.database

  import slickDatabase.profile.api._
  import system.dispatcher

  override def get(key: Key): Future[Option[Value]] =
    for {
      rows <- db.run(queries.selectByKey(key).result)
    } yield rows.headOption.map(r => Value(r.typeUrl, ByteString(r.state)))

  override def update(key: Key, value: Value): Future[Unit] =
    for {
      _ <- db.run(queries.insertOrUpdate(EntityRow(key, value.typeUrl, value.state.toByteBuffer.array())))
    } yield ()

  override def delete(key: Key): Future[Unit] =
    for {
      _ <- db.run(queries.deleteByKey(key))
    } yield ()

}
