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
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString => PbByteString}
import io.cloudstate.proxy.valueentity.store.Store.Key

import scala.concurrent.{ExecutionContext, Future}

/**
 * Represents an interface for persisting value-based entity.
 */
trait Repository {

  /**
   * Retrieve the entity for the given key.
   *
   * @param key to retrieve entity for
   * @return Some(payload) if payload exists for the key and None otherwise
   */
  def get(key: Key): Future[Option[ScalaPbAny]]

  /**
   * Insert the entity with the given key if it not already exists.
   * Update the entity at the given key if it already exists.
   *
   * @param key  to insert or update the entity
   * @param entity to persist
   */
  def update(key: Key, entity: ScalaPbAny): Future[Unit]

  /**
   * Delete the entity with the given key.
   *
   * @param key to delete the entity.
   */
  def delete(key: Key): Future[Unit]

}

class RepositoryImpl(store: Store)(implicit ec: ExecutionContext) extends Repository {

  def get(key: Key): Future[Option[ScalaPbAny]] =
    store
      .get(key)
      .map {
        case Some(entity) =>
          Some(ScalaPbAny(entity.typeUrl, PbByteString.copyFrom(entity.state.toByteBuffer)))

        case None => None
      }

  def update(key: Key, entity: ScalaPbAny): Future[Unit] =
    store.update(key, Store.Value(entity.typeUrl, ByteString.fromArrayUnsafe(entity.value.toByteArray)))

  def delete(key: Key): Future[Unit] = store.delete(key)
}
