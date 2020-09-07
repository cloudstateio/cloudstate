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

import akka.util.ByteString
import com.typesafe.config.Config
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import io.cloudstate.proxy.crud.store.JdbcStoreFactory.{IN_MEMORY, JDBC}

import scala.concurrent.ExecutionContext;

object JdbcStoreFactory {
  final val IN_MEMORY = "in-memory"
  final val JDBC = "jdbc"
}

class JdbcStoreFactory(config: Config)(implicit ec: ExecutionContext) {

  def buildCrudStore(): JdbcStore[Key, ByteString] =
    config.getString("crud.store-type") match {
      case IN_MEMORY => new JdbcInMemoryStore
      case JDBC => buildJdbcCrudStore()
      case other =>
        throw new IllegalArgumentException(s"CRUD store-type must be one of: ${IN_MEMORY} or ${JDBC} but is '$other'")
    }

  private def buildJdbcCrudStore(): JdbcStore[Key, ByteString] = {
    val slickDatabase = JdbcSlickDatabase(config)
    val tableConfiguration = new JdbcCrudStateTableConfiguration(
      config.getConfig("crud.jdbc-state-store")
    )
    val queries = new JdbcCrudStateQueries(slickDatabase.profile, tableConfiguration)
    new JdbcStoreImpl(slickDatabase, queries)
  }

}
