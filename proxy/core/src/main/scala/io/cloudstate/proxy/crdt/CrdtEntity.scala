package io.cloudstate.proxy.crdt

import java.net.URLDecoder

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.Cluster
import akka.cluster.ddata.LWWRegister.Clock
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import io.cloudstate.crdt._
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.entity.{Command, EntityDiscovery, Failure, UserFunctionError}
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.duration.FiniteDuration

object CrdtEntity {

  private final case class Relay(actorRef: ActorRef)

  private final case object StreamClosed

  final case object Stop

  private final case class AnyKey(_id: String) extends Key[ReplicatedData](_id)

  final case class Configuration(
    serviceName: String,
    userFunctionName: String,
    passivationTimeout: Timeout,
    sendQueueSize: Int,
    initialReadTimeout: FiniteDuration,
    writeTimeout: FiniteDuration
  )

  private sealed trait ORMapEntryAction

  private object ORMapEntryAction {

    case object NoAction extends ORMapEntryAction

    case class UpdateEntry(key: ProtoAny, delta: CrdtDelta) extends ORMapEntryAction

    case class AddEntry(entry: ORMapEntry) extends ORMapEntryAction

    case class DeleteThenAdd(key: ProtoAny, state: CrdtState) extends ORMapEntryAction

  }

  private sealed trait CrdtChange

  private object CrdtChange {

    case object NoChange extends CrdtChange

    case class Updated(delta: CrdtDelta) extends CrdtChange

    case object IncompatibleChange extends CrdtChange

  }

  private class CustomClock(clockValue: Long, autoIncrement: Boolean) extends Clock[ProtoAny] {
    override def apply(currentTimestamp: Long, value: ProtoAny): Long =
      if (autoIncrement && clockValue <= currentTimestamp) currentTimestamp + 1
      else clockValue
  }

  private case class InitiatorReply(commandId: Long, actorRef: ActorRef, userFunctionReply: UserFunctionReply)

  def props(client: Crdt, configuration: CrdtEntity.Configuration, entityDiscovery: EntityDiscovery)(implicit mat: Materializer) =
    Props(new CrdtEntity(client, configuration, entityDiscovery))

  private case class UserFunctionProtocolError(message: String) extends RuntimeException(message, null, false, false)

  private case class IncompatibleCrdtChange(message: String) extends RuntimeException(message, null, false, false)

}

/**
  * Optimization idea: Rather than try and calculate changes, implement a custom ReplicatedData type that wraps
  * the rest, and whenever update or mergeDelta is called, keep track of the changes in a shared delta tracking
  * object. That object should get set by this actor, and once present, all calls to merge/mergeDelta/update etc
  * will add changes to the delta tracking object.
  */
final class CrdtEntity(client: Crdt, configuration: CrdtEntity.Configuration, entityDiscovery: EntityDiscovery)(implicit mat: Materializer) extends Actor with Stash with ActorLogging {

  import CrdtEntity._

  private val entityId = URLDecoder.decode(self.path.name, "utf-8")

  private val ddata = DistributedData(context.system)

  import ddata.selfUniqueAddress

  private implicit val cluster: Cluster = Cluster(context.system)
  private val replicator = ddata.replicator
  private val key = AnyKey(configuration.userFunctionName + "-" + entityId)

  private var relay: ActorRef = _
  private var state: Option[ReplicatedData] = _
  private var idCounter = 0l
  private var outstandingOperations = 0
  private var outstanding = Map.empty[Long, ActorRef]
  private var stopping = false

  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  log.debug("Started CRDT entity for service {} with id {}", configuration.serviceName, entityId)

  override def preStart(): Unit = {
    client.handle(Source.actorRef[CrdtStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
      .mapMaterializedValue { ref =>
        self ! Relay(ref)
        NotUsed
      }).runWith(Sink.actorRef(self, StreamClosed))

    // We initially do a read to get the initial state. Try a majority read first in case this is a new node.
    replicator ! Get(key, ReadMajority(configuration.initialReadTimeout))
  }

  override def receive: Receive = {
    case Relay(r) =>
      relay = r
      maybeStart()

    case s@GetSuccess(_, _) =>
      state = Some(s.dataValue)
      maybeStart()

    case NotFound(_, _) =>
      state = None
      maybeStart()

    case GetFailure(_, _) =>
      // Retry with local consistency
      replicator ! Get(key, ReadLocal)

    case DataDeleted(_, _) =>
      if (relay != null) {
        sendDelete()
      }
      context become deleted

    case _ =>
      stash()
  }

  private def sendDelete(): Unit = {
    if (relay != null) {
      sendToRelay(CrdtStreamIn.Message.Deleted(CrdtDelete.defaultInstance))
      relay ! Status.Success(())
      relay = null
    }
    replicator ! Unsubscribe(key, self)
  }

  private def toWireState(state: ReplicatedData): CrdtState = {
    import CrdtState.{State => S}

    CrdtState(state match {
      case gcounter: GCounter =>
        S.Gcounter(GCounterState(gcounter.value.toLong))
      case pncounter: PNCounter =>
        S.Pncounter(PNCounterState(pncounter.value.toLong))
      case gset: GSet[ProtoAny@unchecked] =>
        S.Gset(GSetState(gset.elements.toSeq))
      case orset: ORSet[ProtoAny@unchecked] =>
        S.Orset(ORSetState(orset.elements.toSeq))
      case lwwregister: LWWRegister[ProtoAny@unchecked] =>
        S.Lwwregister(LWWRegisterState(Some(lwwregister.value)))
      case flag: Flag =>
        S.Flag(FlagState(flag.enabled))
      case ormap: ORMap[ProtoAny@unchecked, ReplicatedData@unchecked] =>
        S.Ormap(ORMapState(ormap.entries.map {
          case (k, value) => ORMapEntry(Some(k), Some(toWireState(value)))
        }.toSeq))
      case _ =>
        // todo handle better
        throw new RuntimeException("Unknown CRDT: " + state)
    })
  }

  private def maybeStart() = {

    if (relay != null && state != null) {
      log.debug("Received relay and state, starting.")

      val wireState = state.map(toWireState)

      sendToRelay(CrdtStreamIn.Message.Init(CrdtInit(
        serviceName = configuration.serviceName,
        entityId = entityId,
        state = wireState
      )))

      context become running
      replicator ! Subscribe(key, self)
      unstashAll()
    }

  }

  private def maybeSendAndUpdateState(data: ReplicatedData): Unit = {
    state match {
      case Some(value) =>
        // Fast path, exclude instance equality
        if (!(data eq value)) {
          detectChange(value, data) match {
            case CrdtChange.NoChange =>
            // Nothing to do
            case CrdtChange.IncompatibleChange =>
              throw new RuntimeException(s"Incompatible CRDT change from $value to $data")
            case CrdtChange.Updated(delta) =>
              sendToRelay(CrdtStreamIn.Message.Changed(delta))
          }
        }
      case None =>
        sendToRelay(CrdtStreamIn.Message.State(toWireState(data)))
    }
    state = Some(data)
  }

  private def running: Receive = {

    case c@Changed(_) if outstandingOperations > 0 =>
    // As long as we have outstanding ops, we ignore any changes, to ensure that we never have simultaneous
    // changes of the actor state and the user function state

    case c@Changed(_) =>
      maybeSendAndUpdateState(c.dataValue)

    case Deleted(_) =>
      sendDelete()
      state = None
      context become deleted

    case EntityCommand(_, commandName, payload) =>
      idCounter += 1
      outstanding = outstanding.updated(idCounter, sender())
      outstandingOperations += 1
      sendToRelay(CrdtStreamIn.Message.Command(Command(
        entityId = entityId,
        id = idCounter,
        name = commandName,
        payload = payload
      )))

    case CrdtStreamOut(CrdtStreamOut.Message.Reply(reply)) =>
      // todo validate
      val initiator = outstanding(reply.commandId)

      val userFunctionReply = UserFunctionReply(
        sideEffects = reply.sideEffects,
        message = reply.response match {
          case CrdtReply.Response.Empty => UserFunctionReply.Message.Empty
          case CrdtReply.Response.Reply(payload) => UserFunctionReply.Message.Reply(payload)
          case CrdtReply.Response.Forward(forward) => UserFunctionReply.Message.Forward(forward)
        }
      )

      reply.action match {
        case CrdtReply.Action.Empty =>
          initiator ! userFunctionReply
          outstanding -= reply.commandId
          operationFinished()

        case CrdtReply.Action.Create(create) =>
          if (state.isDefined) {
            crash("Cannot create already created entity")
          } else {
            val crdt = stateToCrdt(create)
            state = Some(stateToCrdt(create))
            replicator ! Update(key, crdt, toDdataWriteConsistency(reply.writeConsistency),
              Some(InitiatorReply(reply.commandId, initiator, userFunctionReply)))(identity)
          }

        case CrdtReply.Action.Delete(_) =>
          replicator ! Delete(key, toDdataWriteConsistency(reply.writeConsistency), Some(InitiatorReply(reply.commandId, initiator, userFunctionReply)))
          state = None
          context become deleted
          replicator ! Unsubscribe(key, self)
          relay ! Status.Success(())
          relay = null

        case CrdtReply.Action.Update(delta) =>
          try {
            val (initial, modify) = deltaToUpdate(delta)
            // Apply to our own state first
            state = Some(modify(state.getOrElse(initial)))
            // And then to the replicator
            replicator ! Update(key, initial, toDdataWriteConsistency(reply.writeConsistency),
              Some(InitiatorReply(reply.commandId, initiator, userFunctionReply)))(modify)
          } catch {
            case e: Exception =>
              crash(e.getMessage, Some(e))
          }
      }

    case UpdateSuccess(_, Some(InitiatorReply(commandId, initiator, userFunctionReply))) =>
      initiator ! userFunctionReply
      outstanding -= commandId
      operationFinished()

    case success@GetSuccess(_, _) =>
      outstandingOperations -= 1
      if (outstandingOperations == 0) {
        maybeSendAndUpdateState(success.dataValue)
      }

    case UpdateTimeout(_, Some(InitiatorReply(commandId, initiator, _))) =>
      outstanding -= commandId
      initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Failed to update CRDT at requested write consistency")))
      crash("Failed to update CRDT at requested write consistency")

    case ModifyFailure(_, error, cause, Some(InitiatorReply(commandId, initiator, _))) =>
      outstanding -= commandId
      initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Error updating CRDT")))
      crash("Error updating CRDT: " + error, Some(cause))

    case CrdtStreamOut(CrdtStreamOut.Message.Failure(failure)) =>
      if (failure.commandId != 0) {
        val initiator = outstanding(failure.commandId)
        outstanding -= failure.commandId
        initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(failure))
        operationFinished()
      }

    case DataDeleted(_, request) =>
      // These are received in response to updates or gets for a deleted key. If it was an update, technically
      // it was successful, it's just that the delete overules it.
      request.foreach {
        case InitiatorReply(commandId, initiator, userFunctionReply) =>
          initiator ! userFunctionReply
          outstanding -= commandId
          sendDelete()
      }

    case StreamClosed =>
      crash("Unexpected entity termination due to stream closure")

    case Status.Failure(cause) =>
      // Means the stream stopped unexpectedly
      crash("Entity crashed", Some(cause))

    case Stop =>
      if (outstanding.isEmpty) {
        context.stop(self)
      } else {
        stopping = true
      }

    case ReceiveTimeout =>
      context.parent ! CrdtEntityManager.Passivate
  }

  private def operationFinished(): Unit = {
    if (stopping) {
      if (outstanding.isEmpty) {
        context.stop(self)
      }
    } else {
      if (outstandingOperations > 1) {
        // Just decrement it
        outstandingOperations -= 1
      } else {
        // Otherwise, do a get to restart pushing deltas to the user function
        replicator ! Get(key, ReadLocal)
      }
    }
  }

  private def crash(message: String, cause: Option[Throwable] = None): Unit = {
    outstanding.values.foreach { actor =>
      actor ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Entity terminating")))
    }
    outstanding = Map.empty

    val error = cause.getOrElse(new Exception(message))
    if (relay != null) {
      relay ! Status.Failure(error)
      relay = null
    }

    entityDiscovery.reportError(UserFunctionError(message))

    throw error
  }

  // We both apply the update to our current state, as well as produce the function that will update the
  // replicators version, since these might not be the same thing.
  private def deltaToUpdate(delta: CrdtDelta): (ReplicatedData, ReplicatedData => ReplicatedData) = {

    import CrdtDelta.{Delta => D}

    delta.delta match {

      case D.Gcounter(GCounterDelta(increment)) =>
        if (increment < 0) {
          throw IncompatibleCrdtChange(s"Cannot decrement a GCounter, requested amount was $increment")
        }
        (GCounter.empty, {
          case gcounter: GCounter => gcounter :+ increment
          case other => throw IncompatibleCrdtChange(s"GCounterDelta is incompatible with CRDT $other")
        })

      case D.Pncounter(PNCounterDelta(change)) =>
        (PNCounter.empty, {
          case pncounter: PNCounter => pncounter :+ change
          case other => throw IncompatibleCrdtChange(s"PNCounterDelta is incompatible with CRDT $other")
        })

      case D.Gset(GSetDelta(added)) =>
        (GSet.empty[ProtoAny], {
          case gset: GSet[ProtoAny@unchecked] => added.foldLeft(gset)((gset, e) => gset + e)
          case other => throw IncompatibleCrdtChange(s"GSetDelta is incompatible with CRDT $other")
        })

      case D.Orset(ORSetDelta(cleared, removed, added)) =>
        (ORSet.empty[ProtoAny], {
          case orset: ORSet[ProtoAny@unchecked] =>
            val maybeCleared = if (cleared) orset.clear(selfUniqueAddress)
            else orset
            val withRemoved = removed.foldLeft(maybeCleared)((orset, key) => orset remove key)
            added.foldLeft(withRemoved)((orset, value) => orset :+ value)
          case other => throw IncompatibleCrdtChange(s"ORSetDelta is incompatible with CRDT $other")
        })

      case D.Lwwregister(LWWRegisterDelta(maybeValue, clock, customClockValue)) =>
        val value = maybeValue.getOrElse(ProtoAny.defaultInstance)
        (LWWRegister.create(value), {
          case lwwregister: LWWRegister[ProtoAny@unchecked] =>
            lwwregister.withValue(selfUniqueAddress, value, toDdataClock(clock, customClockValue))
          case other => throw IncompatibleCrdtChange(s"LWWRegisterDelta is incompatible with CRDT $other")
        })

      case D.Flag(FlagDelta(value)) =>
        (Flag.empty, {
          case flag: Flag =>
            if (value) flag.switchOn
            else if (flag.enabled) throw IncompatibleCrdtChange("Cannot switch off already enabled flag")
            else flag
          case other => throw IncompatibleCrdtChange(s"FlagDelta is incompatible with CRDT $other")
        })

      case D.Ormap(ORMapDelta(cleared, removed, updated, added)) =>
        (ORMap.empty[ProtoAny, ReplicatedData], {
          case ormap: ORMap[ProtoAny@unchecked, ReplicatedData@unchecked] =>

            val maybeCleared = if (cleared) ormap.entries.keySet.foldLeft(ormap)((ormap, key) => ormap remove key)
            else ormap

            val withRemoved = removed.foldLeft(maybeCleared)((ormap, key) => ormap remove key)

            val withUpdated = updated.foldLeft(withRemoved) { case (ormap, ORMapEntryDelta(Some(key), Some(delta))) =>
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
              case (ormap, ORMapEntry(Some(key), Some(state))) =>
                ormap.put(selfUniqueAddress, key, stateToCrdt(state))
            }

          case other => throw IncompatibleCrdtChange(s"ORMap is incompatible with CRDT $other")
        })

      case D.Empty =>
        throw UserFunctionProtocolError("Empty delta")
    }
  }

  private def stateToCrdt(state: CrdtState): ReplicatedData = {
    import CrdtState.{State => S}
    state.state match {
      case S.Gcounter(GCounterState(value)) => GCounter.empty :+ value
      case S.Pncounter(PNCounterState(value)) => PNCounter.empty :+ value
      case S.Gset(GSetState(items)) => items.foldLeft(GSet.empty[ProtoAny])((gset, item) => gset + item)
      case S.Orset(ORSetState(items)) => items.foldLeft(ORSet.empty[ProtoAny])((orset, item) => orset :+ item)
      case S.Lwwregister(LWWRegisterState(value, clock, customClockValue)) => LWWRegister(selfUniqueAddress, value.getOrElse(ProtoAny.defaultInstance), toDdataClock(clock, customClockValue))
      case S.Flag(FlagState(value)) => if (value) Flag.Enabled else Flag.Disabled
      case S.Ormap(ORMapState(items)) => items.foldLeft(ORMap.empty[ProtoAny, ReplicatedData]) {
        case (ormap, ORMapEntry(Some(key), Some(state))) => ormap.put(selfUniqueAddress, key, stateToCrdt(state))
      }
      case S.Empty => throw UserFunctionProtocolError("Unknown state or state not set")
    }
  }

  private def toDdataClock(clock: CrdtClock, customClockValue: Long): Clock[ProtoAny] = {
    clock match {
      case CrdtClock.DEFAULT => LWWRegister.defaultClock
      case CrdtClock.REVERSE => LWWRegister.reverseClock
      case CrdtClock.CUSTOM => new CustomClock(customClockValue, false)
      case CrdtClock.CUSTOM_AUTO_INCREMENT => new CustomClock(customClockValue, true)
      case CrdtClock.Unrecognized(_) => LWWRegister.defaultClock
    }
  }

  private def toDdataWriteConsistency(wc: CrdtReply.WriteConsistency): WriteConsistency = wc match {
    case CrdtReply.WriteConsistency.LOCAL => WriteLocal
    case CrdtReply.WriteConsistency.MAJORITY => WriteMajority(configuration.writeTimeout)
    case CrdtReply.WriteConsistency.ALL => WriteAll(configuration.writeTimeout)
    case _ => WriteLocal
  }

  // We stay active while deleted so we can cache the deletion
  private def deleted: Receive = {
    case Relay(r) =>
      relay = r
      sendDelete()

    case c@Changed(_) =>
    // Ignore

    case Deleted(_) =>
    // Ignore, we know.

    case EntityCommand(_, _, _) =>
      sender() ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Entity deleted")))

    case CrdtStreamOut(CrdtStreamOut.Message.Reply(reply)) =>
      val initiator = outstanding(reply.commandId)

      val userFunctionReply = UserFunctionReply(
        sideEffects = reply.sideEffects,
        message = reply.response match {
          case CrdtReply.Response.Empty => UserFunctionReply.Message.Empty
          case CrdtReply.Response.Reply(payload) => UserFunctionReply.Message.Reply(payload)
          case CrdtReply.Response.Forward(forward) => UserFunctionReply.Message.Forward(forward)
        }
      )

      // Just send the reply. If it's an update, technically it's not invalid to update a CRDT that's been deleted,
      // it's just that the result of merging the delete and the update is that stays deleted. So we don't need to
      // fail.
      initiator ! userFunctionReply
      outstanding -= reply.commandId

    case UpdateSuccess(_, Some(InitiatorReply(commandId, initiator, userFunctionReply))) =>
      initiator ! userFunctionReply
      outstanding -= commandId

    case GetSuccess(_, _) =>
    // Possible if we issued the get before the next operation then deleted. Ignore.

    case UpdateTimeout(_, Some(InitiatorReply(commandId, initiator, _))) =>
      outstanding -= commandId
      initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Failed to update CRDT at requested write consistency")))

    case ModifyFailure(_, error, cause, Some(InitiatorReply(commandId, initiator, _))) =>
      outstanding -= commandId
      initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Error updating CRDT")))

    case CrdtStreamOut(CrdtStreamOut.Message.Failure(failure)) =>
      if (failure.commandId != 0) {
        val initiator = outstanding(failure.commandId)
        outstanding -= failure.commandId
        initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(failure))
      }

    case DataDeleted(_, request) =>
      // These are received in response to updates or gets for a deleted key. If it was an update, technically
      // it was successful, it's just that the delete overules it.
      request.foreach {
        case InitiatorReply(commandId, initiator, userFunctionReply) =>
          initiator ! userFunctionReply
          outstanding -= commandId
          sendDelete()
      }

    case DeleteSuccess(_, Some(InitiatorReply(commandId, initiator, userFunctionReply))) =>
      outstanding -= commandId
      initiator ! userFunctionReply

    case ReplicationDeleteFailure(_, Some(InitiatorReply(commandId, initiator, _))) =>
      outstanding -= commandId
      initiator ! UserFunctionReply(message = UserFunctionReply.Message.Failure(Failure(description = "Failed to delete CRDT at requested write consistency")))

    case StreamClosed =>
    // Ignore

    case ReceiveTimeout =>
      context.parent ! CrdtEntityManager.Passivate

    case Stop =>
      context.stop(self)
  }

  private def sendToRelay(message: CrdtStreamIn.Message): Unit = {
    relay ! CrdtStreamIn(message)
  }

  private def detectChange(original: ReplicatedData, changed: ReplicatedData): CrdtChange = {
    import CrdtChange._
    import CrdtDelta.{Delta => D}

    changed match {

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

      case gset: GSet[ProtoAny@unchecked] =>
        original match {
          case old: GSet[ProtoAny@unchecked] =>
            val diff = gset.elements -- old.elements
            if (old.elements.size + diff.size > gset.elements.size) IncompatibleChange
            else if (diff.isEmpty) NoChange
            else Updated(CrdtDelta(D.Gset(GSetDelta(diff.toSeq))))
          case _ => IncompatibleChange
        }

      case orset: ORSet[ProtoAny@unchecked] =>
        original match {
          case old: ORSet[ProtoAny@unchecked] =>
            // Fast path, just cleared
            if (orset.elements.isEmpty) {
              if (old.elements.isEmpty) {
                NoChange
              } else {
                Updated(CrdtDelta(D.Orset(ORSetDelta(
                  cleared = true
                ))))
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
                  Updated(CrdtDelta(D.Orset(ORSetDelta(
                    cleared = true,
                    added = orset.elements.toSeq
                  ))))
                } else {
                  Updated(CrdtDelta(D.Orset(ORSetDelta(
                    removed = removed.toSeq,
                    added = added.toSeq
                  ))))
                }
              }
            }
          case _ => IncompatibleChange
        }

      case lwwregister: LWWRegister[ProtoAny@unchecked] =>
        original match {
          case old: LWWRegister[ProtoAny@unchecked] =>
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

      case ormap: ORMap[ProtoAny@unchecked, ReplicatedData@unchecked] =>

        import ORMapEntryAction._
        original match {

          case old: ORMap[ProtoAny@unchecked, ReplicatedData@unchecked] =>

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
                Updated(CrdtDelta(D.Ormap(ORMapDelta(
                  removed = allDeleted.toSeq,
                  updated = updated,
                  added = added
                ))))
              }
            }

          case _ => IncompatibleChange

        }

      case _ =>
        // todo handle better
        throw new RuntimeException("Unknown CRDT: " + changed)

    }
  }


}
