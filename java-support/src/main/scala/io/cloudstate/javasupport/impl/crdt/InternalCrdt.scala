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

package io.cloudstate.javasupport.impl.crdt

import io.cloudstate.javasupport.crdt.Crdt
import io.cloudstate.protocol.crdt.CrdtDelta

private[crdt] trait InternalCrdt extends Crdt {
  def name: String
  def hasDelta: Boolean
  def delta: CrdtDelta.Delta
  def resetDelta(): Unit
  def applyDelta: PartialFunction[CrdtDelta.Delta, Unit]
}
