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

import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.crdt.CrdtDelta

private[crdt] object CrdtDeltaTransformer {

  def create(delta: CrdtDelta, anySupport: AnySupport): InternalCrdt = {
    val crdt = delta.delta match {
      case CrdtDelta.Delta.Gcounter(_) =>
        new GCounterImpl
      case CrdtDelta.Delta.Pncounter(_) =>
        new PNCounterImpl
      case CrdtDelta.Delta.Gset(_) =>
        new GSetImpl[Any](anySupport)
      case CrdtDelta.Delta.Orset(_) =>
        new ORSetImpl[Any](anySupport)
      case CrdtDelta.Delta.Flag(_) =>
        new FlagImpl
      case CrdtDelta.Delta.Lwwregister(_) =>
        new LWWRegisterImpl[Any](anySupport)
      case CrdtDelta.Delta.Ormap(_) =>
        new ORMapImpl[Any, InternalCrdt](anySupport)
      case CrdtDelta.Delta.Vote(_) =>
        new VoteImpl
    }
    crdt.applyDelta(delta.delta)
    crdt
  }

}
