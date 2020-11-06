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

import akka.util.ByteString
import io.cloudstate.proxy.valueentity.store.Store
import io.cloudstate.proxy.valueentity.store.Store.Key
import io.cloudstate.proxy.valueentity.store.jdbc.JdbcEntityTable.EntityRow

import scala.concurrent.{ExecutionContext, Future}

private[store] final class JdbcStore(slickDatabase: JdbcSlickDatabase, queries: JdbcEntityQueries)(
    implicit ec: ExecutionContext
) extends Store[Key, ByteString] {

  import slickDatabase.profile.api._

  private val db = slickDatabase.database

  override def get(key: Key): Future[Option[ByteString]] =
    for {
      rows <- db.run(queries.selectByKey(key).result)
    } yield rows.headOption.map(r => ByteString(r.state))

  override def update(key: Key, value: ByteString): Future[Unit] =
    for {
      _ <- db.run(queries.insertOrUpdate(EntityRow(key, value.toByteBuffer.array())))
    } yield ()

  override def delete(key: Key): Future[Unit] =
    for {
      _ <- db.run(queries.deleteByKey(key))
    } yield ()

}
