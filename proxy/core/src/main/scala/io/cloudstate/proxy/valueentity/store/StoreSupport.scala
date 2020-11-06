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
import com.typesafe.config.Config
import StoreSupport.{IN_MEMORY, JDBC}
import io.cloudstate.proxy.valueentity.store.Store.Key
import io.cloudstate.proxy.valueentity.store.jdbc.{
  JdbcEntityQueries,
  JdbcEntityTableConfiguration,
  JdbcSlickDatabase,
  JdbcStore
}

import scala.concurrent.ExecutionContext;

object StoreSupport {
  final val IN_MEMORY = "in-memory"
  final val JDBC = "jdbc"
}

trait StoreSupport {

  def createStore(config: Config)(implicit ec: ExecutionContext): Store[Key, ByteString] =
    config.getString("value-entity-persistence-store.store-type") match {
      case IN_MEMORY => new InMemoryStore
      case JDBC => createJdbcStore(config)
      case other =>
        throw new IllegalArgumentException(
          s"Value Entity store-type must be one of: ${IN_MEMORY} or ${JDBC} but is '$other'"
        )
    }

  private def createJdbcStore(config: Config)(implicit ec: ExecutionContext): Store[Key, ByteString] = {
    val slickDatabase = JdbcSlickDatabase(config)
    val tableConfiguration = new JdbcEntityTableConfiguration(
      config.getConfig("value-entity-persistence-store.jdbc-state-store")
    )
    val queries = new JdbcEntityQueries(slickDatabase.profile, tableConfiguration)
    new JdbcStore(slickDatabase, queries)
  }

}
