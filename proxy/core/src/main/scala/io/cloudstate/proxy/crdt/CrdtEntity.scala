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

import java.net.URLDecoder

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.entity.{
  ClientAction,
  Command,
  EntityDiscovery,
  Failure,
  StreamCancelled,
  UserFunctionError
}
import io.cloudstate.proxy.crdt.WireTransformer.CrdtChange
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.duration.FiniteDuration

object CrdtEntity {

  private final case class Relay(actorRef: ActorRef)

  /**
   * This is sent by Akka streams when the gRPC stream to the user function has closed - which typically shouldn't
   * happen unless it crashes for some reason.
   */
  final case object EntityStreamClosed

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

  private final case class InitiatorReply(commandId: Long, userFunctionReply: UserFunctionReply, endStream: Boolean)

  def props(client: Crdt, configuration: CrdtEntity.Configuration, entityDiscovery: EntityDiscovery)(
      implicit mat: Materializer
  ) =
    Props(new CrdtEntity(client, configuration, entityDiscovery))

  private final case class Initiator(commandId: Long, actorRef: ActorRef, streamed: Boolean)

  private final case class StreamedCommandSourceMaterialized(commandId: Long, command: EntityCommand)

  /**
   * We send this to ourselves when a streamed command stream terminates.
   */
  private final case class StreamEnded(commandId: Long) extends DeadLetterSuppression
}

/**
 * Optimization idea: Rather than try and calculate changes, implement a custom ReplicatedData type that wraps
 * the rest, and whenever update or mergeDelta is called, keep track of the changes in a shared delta tracking
 * object. That object should get set by this actor, and once present, all calls to merge/mergeDelta/update etc
 * will add changes to the delta tracking object.
 *
 * So here's the general principle of how this actor works.
 *
 * - The actor first establishes a stream to the user function as well as fetches the current state of the entity.
 *   Until both of those are returned, it stashes commands, and once it has both, it unstashes.
 * - When a command is received, if the command is streamed, we need to respond with a Source that materializes to
 *   an actor ref that we can send replies to, so that gets done first, otherwise we go straight to command handling
 *   logic.
 * - The actor seeks to keep its state in sync with the user functions state. The user functions state is not a CRDT,
 *   so at any one time, only one of them may be allowed to update their state, otherwise concurrent updates won't be
 *   able to be reconciled. There are two times that the user function is allowed to update its state, one is while
 *   it's handling a command, the other is while it's handling a stream cancelled. If it is not currently handling a
 *   command or a stream cancelled, then the actor is free to update the state, and push deltas to the user function
 *   to keep it in sync. The outstandingMutatingRequests variable is used to track which mode we are in, if greater
 *   than zero, we are not allowed to update our state except on direction by the user function.
 * - We use a replicator subscription to receive state updates. If outstandingMutatingRequests is not zero, we ignore
 *   any change events from that subscription, otherwise, we convert them to deltas and forward them to the user
 *   function.
 * - When we receive a command, we do the following:
 *   - Increment outstanding mutating operations
 *   - Forward the command to the user function
 * - When we receive a reply from the user function, we do the following:
 *   - Perform any update as required by the user function
 *   - Send a reply back to the stream/initiator of the command
 *   - If there is more than one outstanding mutating operation, we just decrement it, and we're done.
 *   - Otherwise, we don't decrement yet. Instead, because we may have ignored some updates while the operations were
 *     underway, we do a local get on the replicator.
 *   - When we get the response (either success or not found) we decrement outstanding mutating operations, and then
 *     check if it's zero (a command have have arrived while we were doing the read), and if it is, then we calculate
 *     and send any deltas found, and we're done.
 * - Similar logic is also used for stream cancelled messages.
 */
final class CrdtEntity(client: Crdt, configuration: CrdtEntity.Configuration, entityDiscovery: EntityDiscovery)(
    implicit mat: Materializer
) extends Actor
    with Stash
    with ActorLogging {

  import CrdtEntity._

  private[this] final val entityId = URLDecoder.decode(self.path.name, "utf-8")

  private[this] final val ddata = DistributedData(context.system)

  import ddata.selfUniqueAddress

  private[this] final implicit val cluster: Cluster = Cluster(context.system)
  private[this] final implicit def clusterState: CurrentClusterState = cluster.state
  private[this] final val replicator = ddata.replicator
  private[this] final val key = AnyKey(configuration.userFunctionName + "-" + entityId)

  private[this] final var relay: ActorRef = _
  private[this] final var state: Option[ReplicatedData] = _
  private[this] final var idCounter = 0L
  // This is used to know whether there are currently outstanding operations on the user function where it could change
  // its state. To ensure we stay in sync, we don't respond to any entity changes during this time.
  private[this] final var outstandingMutatingOperations = 0
  // Outstanding operations waiting to send a reply. Almost equivalent to outstandingMutatingOperations, except that
  // operations are removed from this map before the above is decremented, since the reply is sent in parallel to
  // requesting the updated state.
  private[this] final var outstanding = Map.empty[Long, Initiator]
  private[this] final var streamedCalls = Map.empty[Long, ActorRef]
  private[this] final var closingStreams = Set.empty[Long]
  private[this] final var stopping = false

  implicit val ec = context.dispatcher

  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  log.debug("Started CRDT entity for service {} with id {}", configuration.serviceName, entityId)

  override def preStart(): Unit = {
    client
      .handle(
        Source
          .actorRef[CrdtStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
          .mapMaterializedValue { ref =>
            self ! Relay(ref)
            NotUsed
          }
      )
      .runWith(Sink.actorRef(self, EntityStreamClosed))

    // We initially do a read to get the initial state. Try a majority read first in case this is a new node.
    replicator ! Get(key, ReadMajority(configuration.initialReadTimeout))
  }

  override def postStop(): Unit = {
    outstanding.values.foreach { initiator =>
      failCommand(initiator.commandId, "Entity terminated unexpectedly")
    }
    streamedCalls.values.foreach { _ ! Status.Failure(new RuntimeException("Entity terminated unexpectedly")) }
  }

  override def receive: Receive = {
    case Relay(r) =>
      relay = r
      maybeStart()

    case s @ GetSuccess(_, _) =>
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

  private def maybeStart() =
    if (relay != null && state != null) {
      log.debug("{} - Received relay and state, starting.", entityId)

      val wireState = state.map(WireTransformer.toWireState)

      sendToRelay(
        CrdtStreamIn.Message.Init(
          CrdtInit(
            serviceName = configuration.serviceName,
            entityId = entityId,
            state = wireState
          )
        )
      )

      context become running
      replicator ! Subscribe(key, self)
      unstashAll()
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
              throw new RuntimeException(s"Incompatible CRDT change from $value to $data for entity $entityId")
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

    case c @ Changed(_) if outstandingMutatingOperations > 0 =>
    // As long as we have outstanding ops, we ignore any changes, to ensure that we never have simultaneous
    // changes of the actor state and the user function state

    case c @ Changed(_) =>
      maybeSendAndUpdateState(c.dataValue)

    case Deleted(_) =>
      sendDelete()
      state = None
      context become deleted

    case command: EntityCommand =>
      idCounter += 1
      val commandId = idCounter
      if (command.streamed) {
        // Delay handling the command until the source we return is materialized
        sender() ! Source
          .actorRef(configuration.sendQueueSize, OverflowStrategy.fail)
          .watchTermination()(Keep.both)
          .mapMaterializedValue {
            case (streamActorRef, terminated) =>
              // Send from the stream so that replies go to the stream
              self.tell(StreamedCommandSourceMaterialized(commandId, command), streamActorRef)
              terminated.onComplete { result =>
                // If it's a fail, that can only have been generated by us, so ignore it.
                if (result.isSuccess) {
                  self ! StreamEnded(commandId)
                }
              }
          }
      } else {
        handleCommand(commandId, command)
      }

    case StreamedCommandSourceMaterialized(commandId, command) =>
      handleCommand(commandId, command)

    case CrdtStreamOut(CrdtStreamOut.Message.Reply(reply), _) =>
      val userFunctionReply = UserFunctionReply(reply.clientAction, reply.sideEffects)
      outstanding.get(reply.commandId) match {

        case Some(Initiator(_, actorRef, streamed)) =>
          if (streamed && reply.streamed) {
            if (closingStreams(reply.commandId)) {
              sendToRelay(
                CrdtStreamIn.Message.StreamCancelled(
                  StreamCancelled(
                    entityId,
                    reply.commandId
                  )
                )
              )
              closingStreams -= reply.commandId
            }
          } else if (streamed) {
            if (closingStreams(reply.commandId)) {
              outstandingMutatingOperations -= 1
              closingStreams -= reply.commandId
            }
          } else if (reply.streamed) {
            crash(s"Streamed reply to non streamed command ${reply.commandId}")
          }

          val stateAction = reply.stateAction.getOrElse(CrdtStateAction.defaultInstance)
          performAction(reply.commandId, stateAction, userFunctionReply, streamed && !reply.streamed)

        case None =>
          crash(s"Received reply for entity id $entityId for unknown command ${reply.commandId}")
      }

    case CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(message), _) =>
      streamedCalls.get(message.commandId) match {
        case Some(actorRef) =>
          if (message.clientAction.isDefined || message.sideEffects.nonEmpty) {
            actorRef ! UserFunctionReply(message.clientAction, message.sideEffects)
          }
          if (message.endStream) {
            actorRef ! Status.Success(Done)
            streamedCalls -= message.commandId
            if (closingStreams(message.commandId)) {
              closingStreams -= message.commandId
              operationFinished()
            }
          }
        case None =>
          entityDiscovery.reportError(
            UserFunctionError("Received streamed message for unknown command id: " + message.commandId)
          )
      }

    case CrdtStreamOut(CrdtStreamOut.Message.StreamCancelledResponse(response), _) =>
      performAction(response.commandId,
                    response.stateAction.getOrElse(CrdtStateAction.defaultInstance),
                    UserFunctionReply(None, response.sideEffects),
                    false)

    case StreamEnded(commandId) =>
      streamedCalls.get(commandId) match {
        case Some(_) =>
          outstandingMutatingOperations += 1
          sendToRelay(CrdtStreamIn.Message.StreamCancelled(StreamCancelled(entityId, commandId)))
          closingStreams += commandId
          streamedCalls -= commandId
        case None =>
        // Ignore, we will get a stream ended command both when the client cancels, and when we close.
      }

    case UpdateSuccess(_, Some(InitiatorReply(commandId, userFunctionReply, endStream))) =>
      sendReplyToInitiator(commandId, userFunctionReply, endStream)

    case success @ GetSuccess(_, _) =>
      outstandingMutatingOperations -= 1
      if (outstandingMutatingOperations == 0) {
        maybeSendAndUpdateState(success.dataValue)
      }

    case NotFound(_, _) =>
      outstandingMutatingOperations -= 1

    case UpdateTimeout(_, Some(InitiatorReply(commandId, _, _))) =>
      failCommandAndCrash(commandId, "Failed to update CRDT at requested write consistency", None)

    case ModifyFailure(_, error, cause, Some(InitiatorReply(commandId, _, _))) =>
      failCommandAndCrash(commandId, "Error updating CRDT: " + error, Some(cause))

    case CrdtStreamOut(CrdtStreamOut.Message.Failure(failure), _) =>
      if (failure.commandId != 0) {
        failCommand(failure.commandId, failure.description)
      }

    case DataDeleted(_, request) =>
      // These are received in response to updates or gets for a deleted key. If it was an update, technically
      // it was successful, it's just that the delete overules it.
      request.foreach {
        case InitiatorReply(commandId, userFunctionReply, _) =>
          sendReplyToInitiator(commandId, userFunctionReply, true)
          sendDelete()
      }

    case EntityStreamClosed =>
      crash("Unexpected entity termination due to stream closure")

    case Status.Failure(cause) =>
      // Means the stream stopped unexpectedly
      crash("Entity crashed", Some(cause))

    case Stop =>
      if (outstanding.isEmpty) {
        // If there are streamed calls then terminate them before we stop ourselves.
        streamedCalls.foreach {
          case (commandId, actorRef) =>
            actorRef ! Status.Success(Done)
            streamedCalls -= commandId
        }
        context.stop(self)
      } else {
        stopping = true
      }

    case ReceiveTimeout =>
      if (streamedCalls.isEmpty) {
        context.parent ! CrdtEntityManager.Passivate
      }
  }

  private def handleCommand(commandId: Long, command: EntityCommand) = {
    if (command.streamed) {
      streamedCalls = streamedCalls.updated(commandId, sender())
    }
    outstanding = outstanding.updated(commandId, Initiator(commandId, sender(), command.streamed))

    outstandingMutatingOperations += 1
    sendToRelay(
      CrdtStreamIn.Message.Command(
        Command(
          entityId = entityId,
          id = commandId,
          name = command.name,
          payload = command.payload,
          streamed = command.streamed
        )
      )
    )
  }

  private def sendReplyToInitiator(commandId: Long, reply: UserFunctionReply, terminate: Boolean) = {
    // Send it either to the outstanding initiator, or the stream calls initiator, but not both
    if (outstanding.contains(commandId)) {
      outstanding(commandId).actorRef ! reply
      outstanding -= commandId
      operationFinished()
    } else if (streamedCalls.contains(commandId)) {
      streamedCalls(commandId) ! reply
    } else if (closingStreams.contains(commandId)) {
      operationFinished()
      closingStreams -= commandId
    }

    if (terminate) {
      if (streamedCalls.contains(commandId)) {
        streamedCalls(commandId) ! Status.Success(Done)
        streamedCalls -= commandId
      }

      closingStreams -= commandId
    }
  }

  private def failCommand(commandId: Long, message: String): Unit = {
    val reply = UserFunctionReply(
      Some(
        ClientAction(
          ClientAction.Action.Failure(Failure(description = "Failed to update CRDT at requested write consistency"))
        )
      )
    )

    sendReplyToInitiator(commandId, reply, true)
  }

  private def failCommandAndCrash(commandId: Long, message: String, cause: Option[Throwable]) = {
    failCommand(commandId, message)
    crash("Failed to update CRDT at requested write consistency")
  }

  private def performAction(commandId: Long,
                            stateAction: CrdtStateAction,
                            userFunctionReply: UserFunctionReply,
                            endStream: Boolean) =
    stateAction.action match {
      case CrdtStateAction.Action.Empty =>
        sendReplyToInitiator(commandId, userFunctionReply, false)

      case CrdtStateAction.Action.Create(create) =>
        if (state.isDefined) {
          crash("Cannot create already created entity")
        } else {
          val crdt = WireTransformer.stateToCrdt(create)
          state = Some(WireTransformer.stateToCrdt(create))
          replicator ! Update(key,
                              crdt,
                              toDdataWriteConsistency(stateAction.writeConsistency),
                              Some(InitiatorReply(commandId, userFunctionReply, endStream)))(identity)
        }

      case CrdtStateAction.Action.Delete(_) =>
        replicator ! Delete(key,
                            toDdataWriteConsistency(stateAction.writeConsistency),
                            Some(InitiatorReply(commandId, userFunctionReply, endStream)))
        state = None
        context become deleted
        replicator ! Unsubscribe(key, self)
        relay ! Status.Success(())
        relay = null

      case CrdtStateAction.Action.Update(delta) =>
        try {
          val (initial, modify) = WireTransformer.deltaToUpdate(delta)
          // Apply to our own state first
          state = Some(modify(state.getOrElse(initial)))
          // And then to the replicator
          replicator ! Update(key,
                              initial,
                              toDdataWriteConsistency(stateAction.writeConsistency),
                              Some(InitiatorReply(commandId, userFunctionReply, endStream)))(modify)
        } catch {
          case e: Exception =>
            crash(e.getMessage, Some(e))
        }
    }

  private def operationFinished(): Unit =
    if (stopping) {
      if (outstanding.isEmpty) {
        context.stop(self)
      }
    } else {
      if (outstandingMutatingOperations > 1) {
        // Just decrement it
        outstandingMutatingOperations -= 1
      } else {
        // Otherwise, do a get to restart pushing deltas to the user function
        replicator ! Get(key, ReadLocal)
      }
    }

  private def crash(message: String, cause: Option[Throwable] = None): Unit = {
    val reply = UserFunctionReply(
      Some(ClientAction(ClientAction.Action.Failure(Failure(description = "Entity terminating"))))
    )
    outstanding.values.foreach { initiator =>
      initiator.actorRef ! reply
      streamedCalls -= initiator.commandId
    }
    outstanding = Map.empty
    streamedCalls.values.foreach { actorRef =>
      actorRef ! reply
    }
    streamedCalls = Map.empty

    val error = cause.getOrElse(new Exception(message))
    if (relay != null) {
      relay ! Status.Failure(error)
      relay = null
    }

    entityDiscovery.reportError(UserFunctionError(message))

    throw error
  }

  private def toDdataWriteConsistency(wc: CrdtWriteConsistency): WriteConsistency = wc match {
    case CrdtWriteConsistency.LOCAL => WriteLocal
    case CrdtWriteConsistency.MAJORITY => WriteMajority(configuration.writeTimeout)
    case CrdtWriteConsistency.ALL => WriteAll(configuration.writeTimeout)
    case _ => WriteLocal
  }

  // We stay active while deleted so we can cache the deletion
  private def deleted: Receive = {
    case Relay(r) =>
      relay = r
      sendDelete()

    case c @ Changed(_) =>
    // Ignore

    case Deleted(_) =>
    // Ignore, we know.

    case EntityCommand(_, _, _, streamed, _, _) =>
      val reply = UserFunctionReply(
        Some(ClientAction(ClientAction.Action.Failure(Failure(description = "Entity deleted"))))
      )
      if (streamed) {
        sender() ! Source.single(reply)
        sender() ! Status.Success(Done)
      } else {
        sender() ! reply
      }

    case CrdtStreamOut(CrdtStreamOut.Message.Reply(reply), _) =>
      val userFunctionReply = UserFunctionReply(
        sideEffects = reply.sideEffects,
        clientAction = reply.clientAction
      )
      // Just send the reply. If it's an update, technically it's not invalid to update a CRDT that's been deleted,
      // it's just that the result of merging the delete and the update is that stays deleted. So we don't need to
      // fail.
      sendReplyToInitiator(reply.commandId, userFunctionReply, true)

    case CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(message), _) =>
      streamedCalls.get(message.commandId) match {
        case Some(actorRef) =>
          actorRef ! UserFunctionReply(message.clientAction, message.sideEffects)
          actorRef ! Status.Success(Done)
          streamedCalls -= message.commandId
        case None =>
          entityDiscovery.reportError(
            UserFunctionError("Received streamed message for unknown command id: " + message.commandId)
          )
      }

    case CrdtStreamOut(CrdtStreamOut.Message.StreamCancelledResponse(response), _) =>
      if (!closingStreams.contains(response.commandId)) {
        crash("Received stream cancelled response for stream that's not closing: " + response.commandId)
      } else {
        performAction(response.commandId,
                      response.stateAction.getOrElse(CrdtStateAction.defaultInstance),
                      UserFunctionReply(None, response.sideEffects),
                      false)
      }

    case StreamEnded(commandId) =>
    // Ignore, nothing to do

    case UpdateSuccess(_, Some(InitiatorReply(commandId, userFunctionReply, _))) =>
      sendReplyToInitiator(commandId, userFunctionReply, true)

    case GetSuccess(_, _) =>
    // Possible if we issued the get before the next operation then deleted. Ignore.

    case UpdateTimeout(_, Some(InitiatorReply(commandId, _, _))) =>
      failCommand(commandId, "Failed to update CRDT at requested write consistency")

    case ModifyFailure(_, error, cause, Some(InitiatorReply(commandId, _, _))) =>
      failCommand(commandId, "Error Updating CRDT")

    case CrdtStreamOut(CrdtStreamOut.Message.Failure(failure), _) =>
      if (failure.commandId != 0) {
        failCommand(failure.commandId, failure.description)
      }

    case DataDeleted(_, request) =>
      // These are received in response to updates or gets for a deleted key. If it was an update, technically
      // it was successful, it's just that the delete overules it.
      request.foreach {
        case InitiatorReply(commandId, userFunctionReply, _) =>
          sendReplyToInitiator(commandId, userFunctionReply, true)
          sendDelete()
      }

    case DeleteSuccess(_, Some(InitiatorReply(commandId, userFunctionReply, _))) =>
      sendReplyToInitiator(commandId, userFunctionReply, true)

    case ReplicationDeleteFailure(_, Some(InitiatorReply(commandId, _, _))) =>
      failCommand(commandId, "Failed to delete CRDT at requested write consistency")

    case EntityStreamClosed =>
    // Ignore

    case ReceiveTimeout =>
      context.parent ! CrdtEntityManager.Passivate

    case Stop =>
      context.stop(self)
  }

  private def sendToRelay(message: CrdtStreamIn.Message): Unit =
    relay ! CrdtStreamIn(message)

}
