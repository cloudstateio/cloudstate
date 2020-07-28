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

package io.cloudstate.proxy.telemetry

object EventSourcedInstrumentation {
  final case class Context(startTime: Long)
  final case class StashContext(stash: Context, restore: Context)
}

/**
 * Instrumentation SPI for event-sourced entities.
 */
abstract class EventSourcedInstrumentation {
  import EventSourcedInstrumentation._

  /**
   * Record entity activated.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[entityPassivated]]
   */
  def entityActivated(entityName: String): Context

  /**
   * Record entity passivated (actor stopped).
   *
   * @param entityName the entity name
   * @param context the context passed from [[entityActivated]]
   */
  def entityPassivated(entityName: String, context: Context): Unit

  /**
   * Record entity failed (unexpected termination or crash).
   *
   * @param entityName the entity name
   */
  def entityFailed(entityName: String): Unit

  /**
   * Record entity recovery started (loading snapshots and events).
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[recoveryCompleted]]
   */
  def recoveryStarted(entityName: String): Context

  /**
   * Record entity recovery completed (loading snapshots and events).
   *
   * @param entityName the entity name
   * @param context the context passed from [[recoveryStarted]]
   */
  def recoveryCompleted(entityName: String, context: Context): Unit

  /**
   * Record command received.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[commandCompleted]]
   */
  def commandReceived(entityName: String): Context

  /**
   * Record command stashed (currently processing).
   *
   * @param entityName the entity name
   * @param context the context from [[commandReceived]]
   * @return the context that will be passed to [[commandUnstashed]]
   */
  def commandStashed(entityName: String, context: Context): StashContext

  /**
   * Record command unstashed (ready to process).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandStashed]]
   */
  def commandUnstashed(entityName: String, context: StashContext): Unit

  /**
   * Record command started processing (sending command to user function).
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[commandProcessed]]
   */
  def commandStarted(entityName: String): Context

  /**
   * Record command completed processing (reply received from user function).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandStarted]]
   */
  def commandProcessed(entityName: String, context: Context): Unit

  /**
   * Record command failed.
   *
   * @param entityName the entity name
   */
  def commandFailed(entityName: String): Unit

  /**
   * Record command fully completed (including processing and persisting).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandReceived]]
   */
  def commandCompleted(entityName: String, context: Context): Unit

  /**
   * Record entity persist events started.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[persistCompleted]]
   */
  def persistStarted(entityName: String): Context

  /**
   * Record entity persist events completed.
   *
   * @param entityName the entity name
   * @param context the context passed from [[persistStarted]]
   */
  def persistCompleted(entityName: String, context: Context): Unit

  /**
   * Record event persisted to storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the event
   */
  def eventPersisted(entityName: String, size: Int): Unit

  /**
   * Record event loaded from storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the event
   */
  def eventLoaded(entityName: String, size: Int): Unit

  /**
   * Record snapshot persisted to storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the snapshot
   */
  def snapshotPersisted(entityName: String, size: Int): Unit

  /**
   * Record snapshot loaded from storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the snapshot
   */
  def snapshotLoaded(entityName: String, size: Int): Unit
}

object NoEventSourcedInstrumentation extends EventSourcedInstrumentation {
  import EventSourcedInstrumentation._

  override def entityActivated(entityName: String): Context = null
  override def entityPassivated(entityName: String, context: Context): Unit = ()
  override def entityFailed(entityName: String): Unit = ()
  override def recoveryStarted(entityName: String): Context = null
  override def recoveryCompleted(entityName: String, context: Context): Unit = ()
  override def commandReceived(entityName: String): Context = null
  override def commandStashed(entityName: String, context: Context): StashContext = null
  override def commandUnstashed(entityName: String, context: StashContext): Unit = ()
  override def commandStarted(entityName: String): Context = null
  override def commandProcessed(entityName: String, context: Context): Unit = ()
  override def commandFailed(entityName: String): Unit = ()
  override def commandCompleted(entityName: String, context: Context): Unit = ()
  override def persistStarted(entityName: String): Context = null
  override def persistCompleted(entityName: String, context: Context): Unit = ()
  override def eventPersisted(entityName: String, size: Int): Unit = ()
  override def eventLoaded(entityName: String, size: Int): Unit = ()
  override def snapshotPersisted(entityName: String, size: Int): Unit = ()
  override def snapshotLoaded(entityName: String, size: Int): Unit = ()
}

/**
 * Instrumentation wrapper for an entity instance.
 */
abstract class EventSourcedEntityInstrumentation {
  import EventSourcedInstrumentation._

  def entityActivated(): Unit
  def entityPassivated(): Unit
  def entityFailed(): Unit
  def recoveryStarted(): Unit
  def recoveryCompleted(): Unit
  def commandReceived(): Unit
  def commandStashed(): StashContext
  def commandUnstashed(context: StashContext): Unit
  def commandStarted(): Unit
  def commandProcessed(): Unit
  def commandFailed(): Unit
  def commandCompleted(): Unit
  def persistStarted(): Unit
  def persistCompleted(): Unit
  def eventPersisted(size: Int): Unit
  def eventLoaded(size: Int): Unit
  def snapshotPersisted(size: Int): Unit
  def snapshotLoaded(size: Int): Unit
}

/**
 * Active instrumentation wrapper for an entity instance.
 * Stores instrumentation contexts local to an entity.
 */
class ActiveEventSourcedEntityInstrumentation(entityName: String, eventSourced: EventSourcedInstrumentation)
    extends EventSourcedEntityInstrumentation {
  import EventSourcedInstrumentation._

  // only one of these contexts is active at a time (stashed commands are stored in the stash queue)
  private[this] var entityContext: Context = _
  private[this] var recoveryContext: Context = _
  private[this] var receiveContext: Context = _
  private[this] var startContext: Context = _
  private[this] var persistContext: Context = _

  override def entityActivated(): Unit =
    entityContext = eventSourced.entityActivated(entityName)

  override def entityPassivated(): Unit = {
    eventSourced.entityPassivated(entityName, entityContext)
    entityContext = null
  }

  override def entityFailed(): Unit =
    eventSourced.entityFailed(entityName)

  override def recoveryStarted(): Unit =
    recoveryContext = eventSourced.recoveryStarted(entityName)

  override def recoveryCompleted(): Unit = {
    eventSourced.recoveryCompleted(entityName, recoveryContext)
    recoveryContext = null
  }

  override def commandReceived(): Unit =
    receiveContext = eventSourced.commandReceived(entityName)

  override def commandStashed(): StashContext =
    eventSourced.commandStashed(entityName, eventSourced.commandReceived(entityName))

  override def commandUnstashed(context: StashContext): Unit = {
    eventSourced.commandUnstashed(entityName, context)
    receiveContext = context.restore
  }

  override def commandStarted(): Unit =
    startContext = eventSourced.commandStarted(entityName)

  override def commandProcessed(): Unit = {
    eventSourced.commandProcessed(entityName, startContext)
    startContext = null
  }

  override def commandFailed(): Unit =
    eventSourced.commandFailed(entityName)

  override def commandCompleted(): Unit = {
    eventSourced.commandCompleted(entityName, receiveContext)
    receiveContext = null
  }

  override def persistStarted(): Unit =
    persistContext = eventSourced.persistStarted(entityName)

  override def persistCompleted(): Unit = {
    eventSourced.persistCompleted(entityName, persistContext)
    persistContext = null
  }

  override def eventPersisted(size: Int): Unit =
    eventSourced.eventPersisted(entityName, size)

  override def eventLoaded(size: Int): Unit =
    eventSourced.eventLoaded(entityName, size)

  override def snapshotPersisted(size: Int): Unit =
    eventSourced.snapshotPersisted(entityName, size)

  override def snapshotLoaded(size: Int): Unit =
    eventSourced.snapshotLoaded(entityName, size)
}

object NoEventSourcedEntityInstrumentation extends EventSourcedEntityInstrumentation {
  import EventSourcedInstrumentation._

  override def entityActivated(): Unit = ()
  override def entityPassivated(): Unit = ()
  override def entityFailed(): Unit = ()
  override def recoveryStarted(): Unit = ()
  override def recoveryCompleted(): Unit = ()
  override def commandReceived(): Unit = ()
  override def commandStashed(): StashContext = null
  override def commandUnstashed(context: StashContext): Unit = ()
  override def commandStarted(): Unit = ()
  override def commandProcessed(): Unit = ()
  override def commandFailed(): Unit = ()
  override def commandCompleted(): Unit = ()
  override def persistStarted(): Unit = ()
  override def persistCompleted(): Unit = ()
  override def eventPersisted(size: Int): Unit = ()
  override def eventLoaded(size: Int): Unit = ()
  override def snapshotPersisted(size: Int): Unit = ()
  override def snapshotLoaded(size: Int): Unit = ()
}
