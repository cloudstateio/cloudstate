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

import akka.actor.ActorSystem
import io.cloudstate.proxy.valueentity.store.Store.Key
import io.cloudstate.proxy.valueentity.store.Store.Value

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Represents an in-memory implementation of the store for value-based entities.
 */
final class InMemoryStore(system: ActorSystem) extends Store {

  private var store = TrieMap.empty[Key, Value]

  override def get(key: Key): Future[Option[Value]] = Future.successful(store.get(key))

  override def update(key: Key, entity: Value): Future[Unit] = {
    store += key -> entity
    Future.unit
  }

  override def delete(key: Key): Future[Unit] = {
    store -= key
    Future.unit
  }
}
