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

import scala.concurrent.Future

object Store {

  case class Key(persistentId: String, entityId: String)

  /** Value to persist with its type url and its content */
  case class Value(typeUrl: String, state: ByteString)

}

/**
 * A persistent store for value-based entities.
 */
trait Store {

  /**
   * Retrieve the data for the given key.
   *
   * @param key to retrieve data for
   * @return Some(data) if data exists for the key and None otherwise
   */
  def get(key: Store.Key): Future[Option[Store.Value]]

  /**
   * Insert the data with the given key if it not already exists.
   * Update the data at the given key if it already exists.
   *
   * @param key  to insert or update the entity
   * @param value that should be persisted
   */
  def update(key: Store.Key, value: Store.Value): Future[Unit]

  /**
   * Delete the data for the given key.
   *
   * @param key to delete data.
   */
  def delete(key: Store.Key): Future[Unit]

}
