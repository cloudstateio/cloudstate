package com.lightbend.statefulserverless

import akka.actor.{Actor, DeadLetterSuppression, Props, Timers}
import com.lightbend.statefulserverless.StatsCollector.StatsCollectorSettings
import com.typesafe.config.Config
import io.prometheus.client.Gauge

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Collects stats for actions executed.
  *
  * This actor attempts to replicate the stats collection logic in https://github.com/knative/serving/blob/master/pkg/queue/stats.go.
  * That logic records the amount of time spent at each concurrency level, and uses that to periodically report
  * the weighted average concurrency.
  */
object StatsCollector {

  def props(settings: StatsCollectorSettings): Props = Props(new StatsCollector(settings))

  /**
    * A command has been sent to the user function.
    */
  case class CommandSent private (proxied: Boolean)

  val NormalCommandSent = CommandSent(false)
  val ProxiedCommandSent = CommandSent(true)

  /**
    * A reply has been received from the user function.
    */
  case class ReplyReceived private (proxied: Boolean)

  val NormalReplyReceived = ReplyReceived(false)
  val ProxiedReplyReceived = ReplyReceived(true)

  case class StatsCollectorSettings(
    namespace: String,
    configName: String,
    revisionName: String,
    podName: String,
    reportPeriod: FiniteDuration
  ) {
    def this(config: Config) = this(
      namespace = config.getString("namespace"),
      configName = config.getString("config-name"),
      revisionName = config.getString("revision-name"),
      podName = config.getString("pod-name"),
      reportPeriod = config.getDuration("report-period").toMillis.millis
    )
  }

  private case object Tick extends DeadLetterSuppression

  // 1 second in nano seconds
  private val SecondInNanos: Long = 1000000000

  private val DestinationNsLabel = "destination_namespace"
  private val DestinationConfigLabel = "destination_configuration"
  private val DestinationRevLabel    = "destination_revision"
  private val DestinationPodLabel    = "destination_pod"

  private val labels = Array(
    DestinationNsLabel, DestinationConfigLabel, DestinationRevLabel, DestinationPodLabel
  )

  private object gauges {
    // These go here rather than in the actor because if the Actor happens to be instantiated twice, these will
    // be registered twice and an exception will be thrown.
    val OperationsPerSecond = Gauge.build("queue_operations_per_second",
      "Number of operations per second").labelNames(labels: _*).register()
    val ProxiedOperationsPerSecond = Gauge.build("queue_proxied_operations_per_second",
      "Number of proxied operations per second").labelNames(labels: _*).register()
    val AverageConcurrentRequests = Gauge.build("queue_average_concurrent_requests",
      "Number of requests currently being handled by this pod").labelNames(labels: _*).register()
    val AverageProxiedConcurrentRequests = Gauge.build("queue_average_proxied_concurrent_requests",
      "Number of proxied requests currently being handled by this pod").labelNames(labels: _*).register()
  }
}

class StatsCollector(settings: StatsCollectorSettings) extends Actor with Timers {

  import StatsCollector._

  // These need to be in the same order as the labels registered
  private val labelValues = {
    val map = Map(
      DestinationNsLabel -> settings.namespace,
      DestinationConfigLabel -> settings.configName,
      DestinationRevLabel -> settings.revisionName,
      DestinationPodLabel -> settings.podName
    )
    labels.map(map.apply)
  }

  private val operationsPerSecondGauge = gauges.OperationsPerSecond.labels(labelValues: _*)
  private val proxiedOperationsPerSecondGauge = gauges.ProxiedOperationsPerSecond.labels(labelValues:_ *)
  private val averageConcurrentRequestsGauge = gauges.AverageConcurrentRequests.labels(labelValues:_ *)
  private val averageProxiedConcurrentRequestsGauge = gauges.AverageProxiedConcurrentRequests.labels(labelValues: _*)


  private var requestCount: Int = 0
  private var proxiedCount: Int = 0
  private var concurrency: Int = 0
  private var proxiedConcurrency: Int = 0

  private var lastChangedNanos: Long = System.nanoTime()
  // Mutable maps for performance because these will be frequently updated
  // Possible optimisation: use arrays for concurrency levels below a certain threshold
  private val timeNanosOnConcurrency: mutable.Map[Int, Long] = new mutable.HashMap().withDefaultValue(0)
  private val timeNanosOnProxiedConcurrency: mutable.Map[Int, Long] = new mutable.HashMap().withDefaultValue(0)

  private var lastReportedMillis = System.currentTimeMillis()

  // Report every second - this
  timers.startPeriodicTimer("tick", Tick, settings.reportPeriod)

  private def updateState(): Unit = {
    val currentNanos = System.nanoTime()
    val sinceLastNanos = currentNanos - lastChangedNanos
    timeNanosOnConcurrency.update(concurrency, timeNanosOnConcurrency(concurrency) + sinceLastNanos)
    timeNanosOnProxiedConcurrency.update(proxiedConcurrency, timeNanosOnProxiedConcurrency(proxiedConcurrency) + sinceLastNanos)
    lastChangedNanos = currentNanos
  }


  private def weightedAverage(times: mutable.Map[Int, Long]): Double = {

    // This replicates this go code:
    //	var totalTimeUsed time.Duration
    //	for _, val := range times {
    //		totalTimeUsed += val
    //	}
    // 	avg := 0.0
    //	if totalTimeUsed > 0 {
    //		sum := 0.0
    //		for c, val := range times {
    //			sum += float64(c) * val.Seconds()
    //		}
    //		avg = sum / totalTimeUsed.Seconds()
    //	}
    //	return avg
    def toSeconds(value: Long): Double = value.asInstanceOf[Double] / SecondInNanos

    val totalTimeUsed = times.values.sum
    if (totalTimeUsed > 0) {
      times.map {
        case (c, value) => c * toSeconds(value)
      }.sum / toSeconds(totalTimeUsed)
    } else 0.0
  }

  override def receive: Receive = {
    case CommandSent(proxied) =>
      updateState()
      requestCount += 1
      concurrency += 1
      if (proxied) {
        proxiedCount += 1
        proxiedConcurrency += 1
      }
    case ReplyReceived(proxied) =>
      updateState()
      concurrency -= 1
      if (proxied) {
        proxiedConcurrency -= 1
      }
    case Tick =>
      val currentTime = System.currentTimeMillis()
      updateState()
      val reportPeriodSeconds = Math.max(currentTime - lastReportedMillis, 1).asInstanceOf[Double] / 1000

      // fixme * 50 for debugging purposes only
      val avgConcurrency = weightedAverage(timeNanosOnConcurrency) * 50
      val avgProxiedConcurrency = weightedAverage(timeNanosOnProxiedConcurrency) * 50
      println(s"Reporting concurrency of $avgConcurrency with $requestCount requests and current concurrency of $concurrency")
      operationsPerSecondGauge.set(requestCount.asInstanceOf[Double] / reportPeriodSeconds)
      proxiedOperationsPerSecondGauge.set(proxiedCount / reportPeriodSeconds)
      averageConcurrentRequestsGauge.set(avgConcurrency)
      averageProxiedConcurrentRequestsGauge.set(avgProxiedConcurrency)

      lastReportedMillis = currentTime
      requestCount = 0
      proxiedCount = 0
      timeNanosOnConcurrency.clear()
      timeNanosOnProxiedConcurrency.clear()

  }
}
