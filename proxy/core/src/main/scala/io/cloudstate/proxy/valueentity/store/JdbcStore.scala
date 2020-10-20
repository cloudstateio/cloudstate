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

import akka.util.ByteString
import io.cloudstate.proxy.valueentity.store.JdbcValueEntityTable.ValueEntityRow
import io.cloudstate.proxy.valueentity.store.JdbcStore.Key

import scala.concurrent.{ExecutionContext, Future}

object JdbcStore {

  case class Key(persistentId: String, entityId: String)

}

/**
 * Represents an low level interface for accessing a native CRUD database.
 *
 * @tparam K the type for CRUD database key
 * @tparam V the type for CRUD database value
 */
trait JdbcStore[K, V] {

  /**
   * Retrieve the data for the given key.
   *
   * @param key to retrieve data for
   * @return Some(data) if data exists for the key and None otherwise
   */
  def get(key: K): Future[Option[V]]

  /**
   * Insert the data with the given key if it not already exists.
   * Update the data at the given key if it already exists.
   *
   * @param key  to insert or update the entity
   * @param value that should be persisted
   */
  def update(key: K, value: V): Future[Unit]

  /**
   * Delete the data for the given key.
   *
   * @param key to delete data.
   */
  def delete(key: K): Future[Unit]

}

private[store] final class JdbcStoreImpl(slickDatabase: JdbcSlickDatabase, queries: JdbcValueEntityQueries)(
    implicit ec: ExecutionContext
) extends JdbcStore[Key, ByteString] {

  import slickDatabase.profile.api._

  private val db = slickDatabase.database

  override def get(key: Key): Future[Option[ByteString]] =
    for {
      rows <- db.run(queries.selectByKey(key).result)
    } yield rows.headOption.map(r => ByteString(r.state))

  override def update(key: Key, value: ByteString): Future[Unit] =
    for {
      _ <- db.run(queries.insertOrUpdate(ValueEntityRow(key, value.toByteBuffer.array())))
    } yield ()

  override def delete(key: Key): Future[Unit] =
    for {
      _ <- db.run(queries.deleteByKey(key))
    } yield ()

}
