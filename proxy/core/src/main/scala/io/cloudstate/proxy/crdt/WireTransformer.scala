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

package io.cloudstate.proxy.crdt

import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.MemberStatus
import akka.cluster.ddata.LWWRegister.Clock
import akka.cluster.ddata._
import io.cloudstate.protocol.crdt._
import com.google.protobuf.any.{Any => ProtoAny}

/**
 * Transforms wire versions of CRDTs and deltas to/from the actual CRDTs/deltas
 */
object WireTransformer {

  private[this] final val Zero = BigInt(0)

  private def voteState(vote: Vote)(implicit clusterState: CurrentClusterState,
                                    selfUniqueAddress: SelfUniqueAddress): VoteState = {
    var votesFor = 0
    var votes = 0
    var selfVote = false
    clusterState.members.foreach { member =>
      if (member.status == MemberStatus.Up || member.status == MemberStatus.WeaklyUp) {
        votes += 1
        if (vote.state.getOrElse(member.uniqueAddress, Zero).testBit(0)) {
          votesFor += 1
          if (member.uniqueAddress == selfUniqueAddress.uniqueAddress) {
            selfVote = true
          }
        }
      }
    }
    VoteState(votesFor, votes, selfVote)
  }

  def toWireState(state: ReplicatedData)(implicit clusterState: CurrentClusterState,
                                         selfUniqueAddress: SelfUniqueAddress): CrdtState = {
    import CrdtState.{State => S}

    CrdtState(state match {
      case gcounter: GCounter =>
        S.Gcounter(GCounterState(gcounter.value.toLong))
      case pncounter: PNCounter =>
        S.Pncounter(PNCounterState(pncounter.value.toLong))
      case gset: GSet[ProtoAny @unchecked] =>
        S.Gset(GSetState(gset.elements.toSeq))
      case orset: ORSet[ProtoAny @unchecked] =>
        S.Orset(ORSetState(orset.elements.toSeq))
      case lwwregister: LWWRegister[ProtoAny @unchecked] =>
        S.Lwwregister(LWWRegisterState(Some(lwwregister.value)))
      case flag: Flag =>
        S.Flag(FlagState(flag.enabled))
      case ormap: ORMap[ProtoAny @unchecked, ReplicatedData @unchecked] =>
        S.Ormap(ORMapState(ormap.entries.map {
          case (k, value) => ORMapEntry(Some(k), Some(toWireState(value)))
        }.toSeq))
      case vote: Vote =>
        S.Vote(voteState(vote))
      case _ =>
        // todo handle better
        throw new RuntimeException("Unknown CRDT: " + state)
    })
  }

  // We both apply the update to our current state, as well as produce the function that will update the
  // replicators version, since these might not be the same thing.
  def deltaToUpdate(
      delta: CrdtDelta
  )(implicit selfUniqueAddress: SelfUniqueAddress): (ReplicatedData, ReplicatedData => ReplicatedData) = {

    import CrdtDelta.{Delta => D}

    delta.delta match {

      case D.Gcounter(GCounterDelta(increment, _)) =>
        if (increment < 0) {
          throw IncompatibleCrdtChange(s"Cannot decrement a GCounter, requested amount was $increment")
        }
        (GCounter.empty, {
          case gcounter: GCounter => gcounter :+ increment
          case other => throw IncompatibleCrdtChange(s"GCounterDelta is incompatible with CRDT $other")
        })

      case D.Pncounter(PNCounterDelta(change, _)) =>
        (PNCounter.empty, {
          case pncounter: PNCounter => pncounter :+ change
          case other => throw IncompatibleCrdtChange(s"PNCounterDelta is incompatible with CRDT $other")
        })

      case D.Gset(GSetDelta(added, _)) =>
        (GSet.empty[ProtoAny], {
          case gset: GSet[ProtoAny @unchecked] => added.foldLeft(gset)((gset, e) => gset + e)
          case other => throw IncompatibleCrdtChange(s"GSetDelta is incompatible with CRDT $other")
        })

      case D.Orset(ORSetDelta(cleared, removed, added, _)) =>
        (ORSet.empty[ProtoAny], {
          case orset: ORSet[ProtoAny @unchecked] =>
            val maybeCleared =
              if (cleared) orset.clear(selfUniqueAddress)
              else orset
            val withRemoved = removed.foldLeft(maybeCleared)((orset, key) => orset remove key)
            added.foldLeft(withRemoved)((orset, value) => orset :+ value)
          case other => throw IncompatibleCrdtChange(s"ORSetDelta is incompatible with CRDT $other")
        })

      case D.Lwwregister(LWWRegisterDelta(maybeValue, clock, customClockValue, _)) =>
        val value = maybeValue.getOrElse(ProtoAny.defaultInstance)
        (LWWRegister.create(value), {
          case lwwregister: LWWRegister[ProtoAny @unchecked] =>
            lwwregister.withValue(selfUniqueAddress, value, toDdataClock(clock, customClockValue))
          case other => throw IncompatibleCrdtChange(s"LWWRegisterDelta is incompatible with CRDT $other")
        })

      case D.Flag(FlagDelta(value, _)) =>
        (Flag.empty, {
          case flag: Flag =>
            if (value) flag.switchOn
            else if (flag.enabled) throw IncompatibleCrdtChange("Cannot switch off already enabled flag")
            else flag
          case other => throw IncompatibleCrdtChange(s"FlagDelta is incompatible with CRDT $other")
        })

      case D.Ormap(ORMapDelta(cleared, removed, updated, added, _)) =>
        (ORMap.empty[ProtoAny, ReplicatedData], {
          case ormap: ORMap[ProtoAny @unchecked, ReplicatedData @unchecked] =>
            val maybeCleared =
              if (cleared) ormap.entries.keySet.foldLeft(ormap)((ormap, key) => ormap remove key)
              else ormap

            val withRemoved = removed.foldLeft(maybeCleared)((ormap, key) => ormap remove key)

            val withUpdated = updated.foldLeft(withRemoved) {
              case (ormap, ORMapEntryDelta(Some(key), Some(delta), _)) =>
                // While the CRDT we're using won't have changed, the CRDT in the replicator may have, so we detect that.
                ormap.get(key) match {
                  case Some(data) =>
                    try {
                      val (initial, modify) = deltaToUpdate(delta)
                      ormap.updated(selfUniqueAddress, key, initial)(modify)
                    } catch {
                      case IncompatibleCrdtChange(_) =>
                        // The delta is incompatible, the value must have been removed and then added again, so ignore
                        ormap
                    }
                  case None =>
                    // There is no element, it must have been removed, ignore
                    ormap
                }
            }

            added.foldLeft(withUpdated) {
              case (ormap, ORMapEntry(Some(key), Some(state), _)) =>
                ormap.put(selfUniqueAddress, key, stateToCrdt(state))
            }

          case other => throw IncompatibleCrdtChange(s"ORMap is incompatible with CRDT $other")
        })

      case D.Vote(VoteDelta(selfVote, _, _, _)) =>
        (Vote.empty, {
          case vote: Vote =>
            vote.vote(selfVote)
          case other => throw IncompatibleCrdtChange(s"Vote is incompatible with CRDT $other")
        })

      case D.Empty =>
        throw UserFunctionProtocolError("Empty delta")
    }
  }

  def stateToCrdt(state: CrdtState)(implicit selfUniqueAddress: SelfUniqueAddress): ReplicatedData = {
    import CrdtState.{State => S}
    state.state match {
      case S.Gcounter(GCounterState(value, _)) => GCounter.empty :+ value
      case S.Pncounter(PNCounterState(value, _)) => PNCounter.empty :+ value
      case S.Gset(GSetState(items, _)) => items.foldLeft(GSet.empty[ProtoAny])((gset, item) => gset + item)
      case S.Orset(ORSetState(items, _)) => items.foldLeft(ORSet.empty[ProtoAny])((orset, item) => orset :+ item)
      case S.Lwwregister(LWWRegisterState(value, clock, customClockValue, _)) =>
        LWWRegister(selfUniqueAddress, value.getOrElse(ProtoAny.defaultInstance), toDdataClock(clock, customClockValue))
      case S.Flag(FlagState(value, _)) => if (value) Flag.Enabled else Flag.Disabled
      case S.Ormap(ORMapState(items, _)) =>
        items.foldLeft(ORMap.empty[ProtoAny, ReplicatedData]) {
          case (ormap, ORMapEntry(Some(key), Some(state), _)) => ormap.put(selfUniqueAddress, key, stateToCrdt(state))
        }
      case S.Vote(VoteState(_, _, selfVote, _)) => Vote.empty.vote(selfVote)
      case S.Empty => throw UserFunctionProtocolError("Unknown state or state not set")
    }
  }

  private sealed trait ORMapEntryAction

  private object ORMapEntryAction {

    case object NoAction extends ORMapEntryAction

    case class UpdateEntry(key: ProtoAny, delta: CrdtDelta) extends ORMapEntryAction

    case class AddEntry(entry: ORMapEntry) extends ORMapEntryAction

    case class DeleteThenAdd(key: ProtoAny, state: CrdtState) extends ORMapEntryAction

  }

  sealed trait CrdtChange

  object CrdtChange {

    case object NoChange extends CrdtChange

    case class Updated(delta: CrdtDelta) extends CrdtChange

    case object IncompatibleChange extends CrdtChange

  }

  def detectChange(
      original: ReplicatedData,
      changed: ReplicatedData
  )(implicit clusterState: CurrentClusterState, selfUniqueAddress: SelfUniqueAddress): CrdtChange = {
    import CrdtChange._
    import CrdtDelta.{Delta => D}

    changed match {

      // Fast path, especially for ORMap
      case same if original eq changed => NoChange

      case gcounter: GCounter =>
        original match {
          case old: GCounter =>
            if (old.value > gcounter.value) IncompatibleChange
            else if (old.value == gcounter.value) NoChange
            else Updated(CrdtDelta(D.Gcounter(GCounterDelta(gcounter.value.toLong - old.value.toLong))))
          case _ => IncompatibleChange
        }

      case pncounter: PNCounter =>
        original match {
          case old: PNCounter =>
            if (old.value == pncounter.value) NoChange
            else Updated(CrdtDelta(D.Pncounter(PNCounterDelta(pncounter.value.toLong - old.value.toLong))))
          case _ => IncompatibleChange
        }

      case gset: GSet[ProtoAny @unchecked] =>
        original match {
          case old: GSet[ProtoAny @unchecked] =>
            val diff = gset.elements -- old.elements
            if (old.elements.size + diff.size > gset.elements.size) IncompatibleChange
            else if (diff.isEmpty) NoChange
            else Updated(CrdtDelta(D.Gset(GSetDelta(diff.toSeq))))
          case _ => IncompatibleChange
        }

      case orset: ORSet[ProtoAny @unchecked] =>
        original match {
          case old: ORSet[ProtoAny @unchecked] =>
            // Fast path, just cleared
            if (orset.elements.isEmpty) {
              if (old.elements.isEmpty) {
                NoChange
              } else {
                Updated(
                  CrdtDelta(
                    D.Orset(
                      ORSetDelta(
                        cleared = true
                      )
                    )
                  )
                )
              }
            } else {
              val removed = old.elements -- orset.elements
              val added = orset.elements -- old.elements
              if (removed.isEmpty && added.isEmpty) {
                NoChange
              } else {
                // Optimisation, if we're going to end up sending more operations than there are elements in the set,
                // it's cheaper to just clear it and send all the elements
                if (removed.size + added.size > orset.elements.size) {
                  Updated(
                    CrdtDelta(
                      D.Orset(
                        ORSetDelta(
                          cleared = true,
                          added = orset.elements.toSeq
                        )
                      )
                    )
                  )
                } else {
                  Updated(
                    CrdtDelta(
                      D.Orset(
                        ORSetDelta(
                          removed = removed.toSeq,
                          added = added.toSeq
                        )
                      )
                    )
                  )
                }
              }
            }
          case _ => IncompatibleChange
        }

      case lwwregister: LWWRegister[ProtoAny @unchecked] =>
        original match {
          case old: LWWRegister[ProtoAny @unchecked] =>
            if (old.value == lwwregister.value) NoChange
            else Updated(CrdtDelta(D.Lwwregister(LWWRegisterDelta(Some(lwwregister.value)))))
          case _ => IncompatibleChange
        }

      case flag: Flag =>
        original match {
          case old: Flag =>
            if (old.enabled && !flag.enabled) IncompatibleChange
            else if (old.enabled == flag.enabled) NoChange
            else Updated(CrdtDelta(D.Flag(FlagDelta(flag.enabled))))
          case _ => IncompatibleChange
        }

      case ormap: ORMap[ProtoAny @unchecked, ReplicatedData @unchecked] =>
        import ORMapEntryAction._
        original match {

          case old: ORMap[ProtoAny @unchecked, ReplicatedData @unchecked] =>
            if (ormap.isEmpty) {
              if (old.isEmpty) NoChange
              else Updated(CrdtDelta(D.Ormap(ORMapDelta(cleared = true))))
            } else {

              val changes = ormap.entries.map {
                case (k, value) if !old.contains(k) => AddEntry(ORMapEntry(Some(k), Some(toWireState(value))))
                case (k, value) =>
                  detectChange(old.entries(k), value) match {
                    case NoChange => NoAction
                    case IncompatibleChange => DeleteThenAdd(k, toWireState(value))
                    case Updated(delta) => UpdateEntry(k, delta)
                  }
              }.toSeq

              val deleted = old.entries.keySet -- ormap.entries.keys

              val allDeleted = deleted ++ changes.collect {
                  case DeleteThenAdd(k, _) => k
                }
              val updated = changes.collect {
                case UpdateEntry(key, delta) => ORMapEntryDelta(Some(key), Some(delta))
              }
              val added = changes.collect {
                case AddEntry(entry) => entry
                case DeleteThenAdd(key, state) => ORMapEntry(Some(key), Some(state))
              }

              if (allDeleted.isEmpty && updated.isEmpty && added.isEmpty) {
                NoChange
              } else {
                Updated(
                  CrdtDelta(
                    D.Ormap(
                      ORMapDelta(
                        removed = allDeleted.toSeq,
                        updated = updated,
                        added = added
                      )
                    )
                  )
                )
              }
            }

          case _ => IncompatibleChange

        }

      case vote: Vote =>
        original match {
          case old: Vote =>
            val newState = voteState(vote)
            val oldState = voteState(old)

            if (newState != oldState) {
              Updated(
                CrdtDelta(
                  D.Vote(
                    VoteDelta(
                      selfVote = newState.selfVote,
                      votesFor = newState.votesFor,
                      totalVoters = newState.totalVoters
                    )
                  )
                )
              )
            } else {
              NoChange
            }

          case _ => IncompatibleChange
        }

      case _ =>
        // todo handle better
        throw new RuntimeException("Unknown CRDT: " + changed)

    }
  }

  private def toDdataClock(clock: CrdtClock, customClockValue: Long): Clock[ProtoAny] =
    clock match {
      case CrdtClock.DEFAULT => LWWRegister.defaultClock
      case CrdtClock.REVERSE => LWWRegister.reverseClock
      case CrdtClock.CUSTOM => new CustomClock(customClockValue, false)
      case CrdtClock.CUSTOM_AUTO_INCREMENT => new CustomClock(customClockValue, true)
      case CrdtClock.Unrecognized(_) => LWWRegister.defaultClock
    }

  case class IncompatibleCrdtChange(message: String) extends RuntimeException(message, null, false, false)

  private class CustomClock(clockValue: Long, autoIncrement: Boolean) extends Clock[ProtoAny] {
    override def apply(currentTimestamp: Long, value: ProtoAny): Long =
      if (autoIncrement && clockValue <= currentTimestamp) currentTimestamp + 1
      else clockValue
  }
}
