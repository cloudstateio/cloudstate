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

package io.cloudstate.testkit.crdt

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Message => JavaPbMessage}
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.entity.{ClientAction, Command, SideEffect}
import io.cloudstate.testkit.entity.EntityMessages
import io.cloudstate.testkit.eventsourced.EventSourcedMessages.Effects
import scalapb.{GeneratedMessage => ScalaPbMessage}

object CrdtMessages extends EntityMessages {
  import CrdtStreamIn.{Message => InMessage}
  import CrdtStreamOut.{Message => OutMessage}

  case class Effects(
      stateAction: Option[CrdtStateAction] = None,
      sideEffects: Seq[SideEffect] = Seq.empty,
      streamed: Boolean = false,
      endStream: Boolean = false
  ) {
    def withSideEffect(service: String, command: String, message: JavaPbMessage): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous = false)

    def withSideEffect(service: String, command: String, message: JavaPbMessage, synchronous: Boolean): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous)

    def withSideEffect(service: String, command: String, message: ScalaPbMessage): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous = false)

    def withSideEffect(service: String, command: String, message: ScalaPbMessage, synchronous: Boolean): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous)

    def withSideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): Effects =
      copy(sideEffects = sideEffects :+ SideEffect(service, command, payload, synchronous))

    def ++(other: Effects): Effects =
      Effects(stateAction.orElse(other.stateAction),
              sideEffects ++ other.sideEffects,
              streamed || other.streamed,
              endStream || other.endStream)
  }

  object Effects {
    val empty: Effects = Effects()
  }

  def init(serviceName: String, entityId: String): InMessage =
    init(serviceName, entityId, None)

  def init(serviceName: String, entityId: String, delta: CrdtDelta.Delta): InMessage =
    InMessage.Init(CrdtInit(serviceName, entityId, Option(CrdtDelta(delta))))

  def init(serviceName: String, entityId: String, delta: Option[CrdtDelta]): InMessage =
    InMessage.Init(CrdtInit(serviceName, entityId, delta))

  def delta(delta: CrdtDelta.Delta): InMessage =
    InMessage.Delta(CrdtDelta(delta))

  val delete: InMessage =
    InMessage.Delete(CrdtDelete())

  def command(id: Long, entityId: String, name: String): InMessage =
    command(id, entityId, name, streamed = false)

  def command(id: Long, entityId: String, name: String, streamed: Boolean): InMessage =
    command(id, entityId, name, EmptyJavaMessage, streamed)

  def command(id: Long, entityId: String, name: String, payload: JavaPbMessage): InMessage =
    command(id, entityId, name, payload, streamed = false)

  def command(id: Long, entityId: String, name: String, payload: JavaPbMessage, streamed: Boolean): InMessage =
    command(id, entityId, name, messagePayload(payload), streamed)

  def command(id: Long, entityId: String, name: String, payload: ScalaPbMessage): InMessage =
    command(id, entityId, name, payload, streamed = false)

  def command(id: Long, entityId: String, name: String, payload: ScalaPbMessage, streamed: Boolean): InMessage =
    command(id, entityId, name, messagePayload(payload), streamed)

  def command(id: Long, entityId: String, name: String, payload: Option[ScalaPbAny]): InMessage =
    command(id, entityId, name, payload, streamed = false)

  def command(id: Long, entityId: String, name: String, payload: Option[ScalaPbAny], streamed: Boolean): InMessage =
    InMessage.Command(Command(entityId, id, name, payload, streamed))

  def crdtStreamCancelled(id: Long, entityId: String): InMessage =
    InMessage.StreamCancelled(streamCancelled(id, entityId))

  def reply(id: Long, payload: JavaPbMessage): OutMessage =
    reply(id, payload, Effects.empty)

  def reply(id: Long, payload: JavaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: ScalaPbMessage): OutMessage =
    reply(id, payload, Effects.empty)

  def reply(id: Long, payload: ScalaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    crdtReply(id, clientActionReply(payload), effects)

  def forward(id: Long, service: String, command: String, payload: JavaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: JavaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  def forward(id: Long, service: String, command: String, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    crdtReply(id, clientActionForward(service, command, payload), effects)

  def failure(id: Long, description: String): OutMessage =
    crdtReply(id, clientActionFailure(id, description), Effects.empty)

  def crdtReply(id: Long, clientAction: Option[ClientAction], effects: Effects): OutMessage =
    OutMessage.Reply(CrdtReply(id, clientAction, effects.sideEffects, effects.stateAction, effects.streamed))

  def streamed(id: Long, payload: JavaPbMessage): OutMessage =
    streamed(id, payload, Effects.empty)

  def streamed(id: Long, payload: JavaPbMessage, effects: Effects): OutMessage =
    streamed(id, messagePayload(payload), effects)

  def streamed(id: Long, payload: ScalaPbMessage): OutMessage =
    streamed(id, payload, Effects.empty)

  def streamed(id: Long, payload: ScalaPbMessage, effects: Effects): OutMessage =
    streamed(id, messagePayload(payload), effects)

  def streamed(id: Long, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    crdtStreamedMessage(id, clientActionReply(payload), effects)

  def crdtStreamedMessage(id: Long, clientAction: Option[ClientAction], effects: Effects): OutMessage =
    OutMessage.StreamedMessage(CrdtStreamedMessage(id, clientAction, effects.sideEffects, effects.endStream))

  def streamCancelledResponse(id: Long): OutMessage =
    streamCancelledResponse(id, Effects.empty)

  def streamCancelledResponse(id: Long, effects: Effects): OutMessage =
    OutMessage.StreamCancelledResponse(CrdtStreamCancelledResponse(id, effects.sideEffects, effects.stateAction))

  def crdtUpdate(delta: CrdtDelta.Delta): Option[CrdtStateAction] =
    Some(CrdtStateAction(CrdtStateAction.Action.Update(CrdtDelta(delta))))

  def crdtUpdate(delta: CrdtDelta.Delta, writeConsistency: CrdtWriteConsistency): Option[CrdtStateAction] =
    Some(CrdtStateAction(CrdtStateAction.Action.Update(CrdtDelta(delta)), writeConsistency))

  def deltaGCounter(value: Long): CrdtDelta.Delta.Gcounter =
    CrdtDelta.Delta.Gcounter(GCounterDelta(value))

  def deltaPNCounter(value: Long): CrdtDelta.Delta.Pncounter =
    CrdtDelta.Delta.Pncounter(PNCounterDelta(value))

  def deltaGSet(): CrdtDelta.Delta.Gset =
    deltaGSet(Seq.empty)

  def deltaGSet(element: String, elements: String*): CrdtDelta.Delta.Gset =
    deltaGSet(primitiveString(element), elements.map(primitiveString): _*)

  def deltaGSet(element: JavaPbMessage, elements: JavaPbMessage*): CrdtDelta.Delta.Gset =
    deltaGSet(protobufAny(element), elements.map(protobufAny): _*)

  def deltaGSet(element: ScalaPbMessage, elements: ScalaPbMessage*): CrdtDelta.Delta.Gset =
    deltaGSet(protobufAny(element), elements.map(protobufAny): _*)

  def deltaGSet(element: ScalaPbAny, elements: ScalaPbAny*): CrdtDelta.Delta.Gset =
    deltaGSet(element +: elements)

  def deltaGSet(added: Seq[ScalaPbAny]): CrdtDelta.Delta.Gset =
    CrdtDelta.Delta.Gset(GSetDelta(added))

  case class DeltaORSet(cleared: Boolean = false,
                        removed: Seq[ScalaPbAny] = Seq.empty,
                        added: Seq[ScalaPbAny] = Seq.empty) {

    def add(element: JavaPbMessage, elements: JavaPbMessage*): DeltaORSet =
      add(protobufAny(element), elements.map(protobufAny): _*)

    def add(element: ScalaPbMessage, elements: ScalaPbMessage*): DeltaORSet =
      add(protobufAny(element), elements.map(protobufAny): _*)

    def add(element: ScalaPbAny, elements: ScalaPbAny*): DeltaORSet =
      add(element +: elements)

    def add(elements: Seq[ScalaPbAny]): DeltaORSet =
      copy(added = added ++ elements)

    def remove(element: JavaPbMessage, elements: JavaPbMessage*): DeltaORSet =
      remove(protobufAny(element), elements.map(protobufAny): _*)

    def remove(element: ScalaPbMessage, elements: ScalaPbMessage*): DeltaORSet =
      remove(protobufAny(element), elements.map(protobufAny): _*)

    def remove(element: ScalaPbAny, elements: ScalaPbAny*): DeltaORSet =
      remove(element +: elements)

    def remove(elements: Seq[ScalaPbAny]): DeltaORSet =
      copy(removed = removed ++ elements)

    def clear(cleared: Boolean = true): DeltaORSet =
      copy(cleared = cleared)

    def crdtDelta(): CrdtDelta.Delta.Orset =
      CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed, added))
  }

  object DeltaORSet {
    val empty: DeltaORSet = DeltaORSet()
  }

  def deltaLWWRegister(value: JavaPbMessage): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, CrdtClock.DEFAULT)

  def deltaLWWRegister(value: JavaPbMessage, clock: CrdtClock): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, clock, customClock = 0L)

  def deltaLWWRegister(value: JavaPbMessage, clock: CrdtClock, customClock: Long): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(messagePayload(value), clock, customClock)

  def deltaLWWRegister(value: ScalaPbMessage): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, CrdtClock.DEFAULT)

  def deltaLWWRegister(value: ScalaPbMessage, clock: CrdtClock): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, clock, customClock = 0L)

  def deltaLWWRegister(value: ScalaPbMessage, clock: CrdtClock, customClock: Long): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(messagePayload(value), clock, customClock)

  def deltaLWWRegister(value: Option[ScalaPbAny]): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, CrdtClock.DEFAULT)

  def deltaLWWRegister(value: Option[ScalaPbAny], clock: CrdtClock): CrdtDelta.Delta.Lwwregister =
    deltaLWWRegister(value, clock, customClock = 0L)

  def deltaLWWRegister(value: Option[ScalaPbAny], clock: CrdtClock, customClock: Long): CrdtDelta.Delta.Lwwregister =
    CrdtDelta.Delta.Lwwregister(LWWRegisterDelta(value, clock, customClock))

  def deltaFlag(value: Boolean): CrdtDelta.Delta.Flag =
    CrdtDelta.Delta.Flag(FlagDelta(value))

  case class DeltaORMap(cleared: Boolean = false,
                        removed: Seq[ScalaPbAny] = Seq.empty,
                        updated: Seq[(ScalaPbAny, CrdtDelta)] = Seq.empty,
                        added: Seq[(ScalaPbAny, CrdtDelta)] = Seq.empty) {

    def add(key: JavaPbMessage, delta: CrdtDelta): DeltaORMap =
      add(protobufAny(key), delta)

    def add(key: ScalaPbMessage, delta: CrdtDelta): DeltaORMap =
      add(protobufAny(key), delta)

    def add(key: ScalaPbAny, delta: CrdtDelta): DeltaORMap =
      add(Seq(key -> delta))

    def add(entries: Seq[(ScalaPbAny, CrdtDelta)]): DeltaORMap =
      copy(added = added ++ entries)

    def update(key: JavaPbMessage, delta: CrdtDelta): DeltaORMap =
      update(protobufAny(key), delta)

    def update(key: ScalaPbMessage, delta: CrdtDelta): DeltaORMap =
      update(protobufAny(key), delta)

    def update(key: ScalaPbAny, delta: CrdtDelta): DeltaORMap =
      update(Seq(key -> delta))

    def update(entries: Seq[(ScalaPbAny, CrdtDelta)]): DeltaORMap =
      copy(updated = updated ++ entries)

    def remove(key: JavaPbMessage, keys: JavaPbMessage*): DeltaORMap =
      remove(protobufAny(key), keys.map(protobufAny): _*)

    def remove(key: ScalaPbMessage, keys: ScalaPbMessage*): DeltaORMap =
      remove(protobufAny(key), keys.map(protobufAny): _*)

    def remove(key: ScalaPbAny, keys: ScalaPbAny*): DeltaORMap =
      remove(key +: keys)

    def remove(keys: Seq[ScalaPbAny]): DeltaORMap =
      copy(removed = removed ++ keys)

    def clear(cleared: Boolean = true): DeltaORMap =
      copy(cleared = cleared)

    def crdtDelta(): CrdtDelta.Delta.Ormap = {
      val updatedEntries = updated map { case (key, delta) => ORMapEntryDelta(Option(key), Option(delta)) }
      val addedEntries = added map { case (key, delta) => ORMapEntryDelta(Option(key), Option(delta)) }
      CrdtDelta.Delta.Ormap(ORMapDelta(cleared, removed, updatedEntries, addedEntries))
    }
  }

  object DeltaORMap {
    val empty: DeltaORMap = DeltaORMap()
  }

  def deltaVote(selfVote: Boolean, votesFor: Int = 0, totalVoters: Int = 0): CrdtDelta.Delta.Vote =
    CrdtDelta.Delta.Vote(VoteDelta(selfVote, votesFor, totalVoters))

  val crdtDelete: Option[CrdtStateAction] =
    Some(CrdtStateAction(CrdtStateAction.Action.Delete(CrdtDelete())))
}
