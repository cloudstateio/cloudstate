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

import io.prometheus.client.{CollectorRegistry, Counter, Histogram, Summary}

object PrometheusEventSourcedInstrumentation {
  object MetricName {
    final val ActivatedEntitiesTotal = "cloudstate_eventsourced_activated_entities_total"
    final val PassivatedEntitiesTotal = "cloudstate_eventsourced_passivated_entities_total"
    final val EntityActiveTimeSeconds = "cloudstate_eventsourced_entity_active_time_seconds"
    final val FailedEntitiesTotal = "cloudstate_eventsourced_failed_entities_total"
    final val RecoveryTimeSeconds = "cloudstate_eventsourced_recovery_time_seconds"
    final val ReceivedCommandsTotal = "cloudstate_eventsourced_received_commands_total"
    final val StashedCommandsTotal = "cloudstate_eventsourced_stashed_commands_total"
    final val UnstashedCommandsTotal = "cloudstate_eventsourced_unstashed_commands_total"
    final val CommandStashTimeSeconds = "cloudstate_eventsourced_command_stash_time_seconds"
    final val CommandProcessingTimeSeconds = "cloudstate_eventsourced_command_processing_time_seconds"
    final val FailedCommandsTotal = "cloudstate_eventsourced_failed_commands_total"
    final val CompletedCommandsTotal = "cloudstate_eventsourced_completed_commands_total"
    final val CommandTotalTimeSeconds = "cloudstate_eventsourced_command_total_time_seconds"
    final val PersistTimeSeconds = "cloudstate_eventsourced_persist_time_seconds"
    final val PersistedEventsTotal = "cloudstate_eventsourced_persisted_events_total"
    final val PersistedEventBytesTotal = "cloudstate_eventsourced_persisted_event_bytes_total"
    final val LoadedEventsTotal = "cloudstate_eventsourced_loaded_events_total"
    final val LoadedEventBytesTotal = "cloudstate_eventsourced_loaded_event_bytes_total"
    final val PersistedSnapshotsTotal = "cloudstate_eventsourced_persisted_snapshots_total"
    final val PersistedSnapshotBytesTotal = "cloudstate_eventsourced_persisted_snapshot_bytes_total"
    final val LoadedSnapshotsTotal = "cloudstate_eventsourced_loaded_snapshots_total"
    final val LoadedSnapshotBytesTotal = "cloudstate_eventsourced_loaded_snapshot_bytes_total"
  }

  object MetricLabel {
    final val EntityName = "entity_name"
  }

  final val NanosecondsPerSecond = 1e9
}

class PrometheusEventSourcedInstrumentation(registry: CollectorRegistry) extends EventSourcedInstrumentation {
  import EventSourcedInstrumentation._
  import PrometheusEventSourcedInstrumentation._

  // Notes:
  // Using Histograms for command timing metrics, with the default buckets (from 5ms up to 10s).
  // Timing metrics are in seconds, using the Prometheus convention of base units.
  // Not using Summary for command timing metrics, as it currently has terrible performance.
  // HDRHistogram-backed Summary still coming: https://github.com/prometheus/client_java/pull/484

  private val activatedEntitiesTotal: Counter = Counter.build
    .name(MetricName.ActivatedEntitiesTotal)
    .help("Total entity instances that have activated.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val passivatedEntitiesTotal: Counter = Counter.build
    .name(MetricName.PassivatedEntitiesTotal)
    .help("Total entity instances that have passivated.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val entityActiveTimeSeconds: Summary = Summary.build
    .name(MetricName.EntityActiveTimeSeconds)
    .help("Duration that entities are active, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .quantile(0.50, 0.050) // 50th percentile (median), 5% error
    .quantile(0.95, 0.010) // 95th percentile, 1% error
    .quantile(0.99, 0.001) // 99th percentile, 0.1% error
    .quantile(1.00, 0.000) // 100th percentile (max), 0% error
    .maxAgeSeconds(60)
    .ageBuckets(6)
    .register(registry)

  private val failedEntitiesTotal: Counter = Counter.build
    .name(MetricName.FailedEntitiesTotal)
    .help("Total entity instances that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val recoveryTimeSeconds: Histogram = Histogram.build
    .name(MetricName.RecoveryTimeSeconds)
    .help("Duration for entity recovery, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val receivedCommandsTotal: Counter = Counter.build
    .name(MetricName.ReceivedCommandsTotal)
    .help("Total commands received for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val stashedCommandsTotal: Counter = Counter.build
    .name(MetricName.StashedCommandsTotal)
    .help("Total commands stashed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val unstashedCommandsTotal: Counter = Counter.build
    .name(MetricName.UnstashedCommandsTotal)
    .help("Total commands unstashed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandStashTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandStashTimeSeconds)
    .help("Duration that commands are stashed, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandProcessingTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandProcessingTimeSeconds)
    .help("Duration for command processing (until user function reply), in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val failedCommandsTotal: Counter = Counter.build
    .name(MetricName.FailedCommandsTotal)
    .help("Total commands that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val completedCommandsTotal: Counter = Counter.build
    .name(MetricName.CompletedCommandsTotal)
    .help("Total commands completed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandTotalTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandTotalTimeSeconds)
    .help("Total duration for command handling (including stash and persist), in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistTimeSeconds: Histogram = Histogram.build
    .name(MetricName.PersistTimeSeconds)
    .help("Duration for persisting events from a command, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedEventsTotal: Counter = Counter.build
    .name(MetricName.PersistedEventsTotal)
    .help("Total events persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedEventBytesTotal: Counter = Counter.build
    .name(MetricName.PersistedEventBytesTotal)
    .help("Total event sizes persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedEventsTotal: Counter = Counter.build
    .name(MetricName.LoadedEventsTotal)
    .help("Total events loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedEventBytesTotal: Counter = Counter.build
    .name(MetricName.LoadedEventBytesTotal)
    .help("Total event sizes loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedSnapshotsTotal: Counter = Counter.build
    .name(MetricName.PersistedSnapshotsTotal)
    .help("Total snapshots persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedSnapshotBytesTotal: Counter = Counter.build
    .name(MetricName.PersistedSnapshotBytesTotal)
    .help("Total snapshot sizes persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedSnapshotsTotal: Counter = Counter.build
    .name(MetricName.LoadedSnapshotsTotal)
    .help("Total snapshots loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedSnapshotBytesTotal: Counter = Counter.build
    .name(MetricName.LoadedSnapshotBytesTotal)
    .help("Total snapshot sizes loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private def now: Long = System.nanoTime()

  private def elapsedSeconds(context: Context): Double =
    (now - context.startTime) / NanosecondsPerSecond

  private def startSpan(): Context = Context(startTime = now)

  private def startSpan(entityName: String, counter: Counter): Context = {
    counter.labels(entityName).inc()
    startSpan()
  }

  private def endSpan(entityName: String, timer: Histogram, context: Context): Unit =
    timer.labels(entityName).observe(elapsedSeconds(context))

  private def endSpan(entityName: String, counter: Counter, timer: Histogram, context: Context): Unit = {
    counter.labels(entityName).inc()
    endSpan(entityName, timer, context)
  }

  private def endSpan(entityName: String, timer: Summary, context: Context): Unit =
    timer.labels(entityName).observe(elapsedSeconds(context))

  private def endSpan(entityName: String, counter: Counter, timer: Summary, context: Context): Unit = {
    counter.labels(entityName).inc()
    endSpan(entityName, timer, context)
  }

  override def entityActivated(entityName: String): Context =
    startSpan(entityName, activatedEntitiesTotal)

  override def entityPassivated(entityName: String, context: Context): Unit =
    endSpan(entityName, passivatedEntitiesTotal, entityActiveTimeSeconds, context)

  override def entityFailed(entityName: String): Unit =
    failedEntitiesTotal.labels(entityName).inc()

  override def recoveryStarted(entityName: String): Context =
    startSpan()

  override def recoveryCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, recoveryTimeSeconds, context)

  override def commandReceived(entityName: String): Context =
    startSpan(entityName, receivedCommandsTotal)

  override def commandStashed(entityName: String, context: Context): StashContext =
    StashContext(stash = startSpan(entityName, stashedCommandsTotal), restore = context)

  override def commandUnstashed(entityName: String, context: StashContext): Unit =
    endSpan(entityName, unstashedCommandsTotal, commandStashTimeSeconds, context.stash)

  override def commandStarted(entityName: String): Context =
    startSpan()

  override def commandProcessed(entityName: String, context: Context): Unit =
    endSpan(entityName, commandProcessingTimeSeconds, context)

  override def commandFailed(entityName: String): Unit =
    failedCommandsTotal.labels(entityName).inc()

  override def commandCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, completedCommandsTotal, commandTotalTimeSeconds, context)

  override def persistStarted(entityName: String): Context =
    startSpan()

  override def persistCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, persistTimeSeconds, context)

  override def eventPersisted(entityName: String, size: Int): Unit = {
    persistedEventsTotal.labels(entityName).inc()
    persistedEventBytesTotal.labels(entityName).inc(size)
  }

  override def eventLoaded(entityName: String, size: Int): Unit = {
    loadedEventsTotal.labels(entityName).inc()
    loadedEventBytesTotal.labels(entityName).inc(size)
  }

  override def snapshotPersisted(entityName: String, size: Int): Unit = {
    persistedSnapshotsTotal.labels(entityName).inc()
    persistedSnapshotBytesTotal.labels(entityName).inc(size)
  }

  override def snapshotLoaded(entityName: String, size: Int): Unit = {
    loadedSnapshotsTotal.labels(entityName).inc()
    loadedSnapshotBytesTotal.labels(entityName).inc(size)
  }
}
