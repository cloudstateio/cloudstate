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

import akka.grpc.ProtobufSerializer
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
   * Retrieve the payload for the given key.
   *
   * @param key to retrieve payload for
   * @return Some(payload) if payload exists for the key and None otherwise
   */
  def get(key: Key): Future[Option[ScalaPbAny]]

  /**
   * Insert the entity payload with the given key if it not already exists.
   * Update the entity payload at the given key if it already exists.
   *
   * @param key  to insert or update the entity
   * @param payload that should be persisted
   */
  def update(key: Key, payload: ScalaPbAny): Future[Unit]

  /**
   * Delete the entity with the given key.
   *
   * @param key to delete data.
   */
  def delete(key: Key): Future[Unit]

}

object RepositoryImpl {

  private[store] final object EntitySerializer extends ProtobufSerializer[ScalaPbAny] {
    private val separator = ByteString("|")

    override final def serialize(entity: ScalaPbAny): ByteString =
      if (entity.value.isEmpty) {
        ByteString(entity.typeUrl) ++ separator ++ ByteString.empty
      } else {
        ByteString(entity.typeUrl) ++ separator ++ ByteString.fromArrayUnsafe(entity.value.toByteArray)
      }

    override final def deserialize(bytes: ByteString): ScalaPbAny = {
      val separatorIndex = bytes.indexOf(separator.head)
      val (typeUrl, value) = bytes.splitAt(separatorIndex)
      ScalaPbAny(typeUrl.utf8String, PbByteString.copyFrom(value.tail.toByteBuffer))
    }
  }

}

class RepositoryImpl(store: Store, serializer: ProtobufSerializer[ScalaPbAny])(
    implicit ec: ExecutionContext
) extends Repository {

  def this(store: Store)(implicit ec: ExecutionContext) =
    this(store, RepositoryImpl.EntitySerializer)

  def get(key: Key): Future[Option[ScalaPbAny]] =
    store
      .get(key)
      .map {
        case Some(value) => Some(serializer.deserialize(value))
        case None => None
      }

  def update(key: Key, entity: ScalaPbAny): Future[Unit] =
    store.update(key, serializer.serialize(entity))

  def delete(key: Key): Future[Unit] = store.delete(key)
}
