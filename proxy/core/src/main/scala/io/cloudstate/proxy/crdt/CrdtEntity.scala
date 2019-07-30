package io.cloudstate.proxy.crdt

import java.net.URLDecoder

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import io.cloudstate.crdt._
import io.cloudstate.entity.{Command, EntityDiscovery, Failure, UserFunctionError}
import io.cloudstate.proxy.crdt.WireTransformer.CrdtChange
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

  private case class InitiatorReply(commandId: Long, actorRef: ActorRef, userFunctionReply: UserFunctionReply)

  def props(client: Crdt, configuration: CrdtEntity.Configuration, entityDiscovery: EntityDiscovery)(implicit mat: Materializer) =
    Props(new CrdtEntity(client, configuration, entityDiscovery))

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

  private def maybeStart() = {

    if (relay != null && state != null) {
      log.debug("Received relay and state, starting.")

      val wireState = state.map(WireTransformer.toWireState)

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
          WireTransformer.detectChange(value, data) match {
            case CrdtChange.NoChange =>
            // Nothing to do
            case CrdtChange.IncompatibleChange =>
              throw new RuntimeException(s"Incompatible CRDT change from $value to $data")
            case CrdtChange.Updated(delta) =>
              sendToRelay(CrdtStreamIn.Message.Changed(delta))
          }
        }
      case None =>
        sendToRelay(CrdtStreamIn.Message.State(WireTransformer.toWireState(data)))
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
            val crdt = WireTransformer.stateToCrdt(create)
            state = Some(WireTransformer.stateToCrdt(create))
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
            val (initial, modify) = WireTransformer.deltaToUpdate(delta)
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


}
