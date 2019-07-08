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

package io.cloudstate.proxy

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Timers}
import akka.cluster.Cluster
import StatsCollector.StatsCollectorSettings
import io.cloudstate.proxy.autoscaler.AutoscalerMetrics
import com.typesafe.config.Config

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

  def props(settings: StatsCollectorSettings, autoscaler: ActorRef): Props = Props(new StatsCollector(settings, autoscaler))

  /**
    * A request has been received by the proxy server
    */
  case object RequestReceived

  case class ResponseSent(timeNanos: Long)

  /**
    * A command has been sent to the user function.
    */
  case object CommandSent

  /**
    * A reply has been received from the user function.
    */
  case class ReplyReceived private(timeNanos: Long)

  case object DatabaseOperationStarted

  case class DatabaseOperationFinished(timeNanos: Long)

  case class StatsCollectorSettings(
    reportPeriod: FiniteDuration
  ) {
    def this(config: Config) = this(
      reportPeriod = config.getDuration("report-period").toMillis.millis
    )
  }

  private case object Tick extends DeadLetterSuppression

  // 1 second in nano seconds
  private val SecondInNanos: Long = 1000000000
}

class StatsCollector(settings: StatsCollectorSettings, autoscaler: ActorRef) extends Actor with Timers with ActorLogging {

  import StatsCollector._

  private val (address, uniqueAddressLongId) = {
    val cluster = Cluster(context.system)
    (cluster.selfUniqueAddress.address.toString, cluster.selfUniqueAddress.longUid)
  }

  private var commandCount: Int = 0
  private var commandTimeNanos: Long = 0
  private var commandConcurrency: Int = 0
  private val commandTimeNanosOnConcurrency: mutable.Map[Int, Long] = new mutable.HashMap().withDefaultValue(0)
  private var commandLastChangedNanos: Long = System.nanoTime()

  private var requestCount: Int = 0
  private var requestTimeNanos: Long = 0
  private var requestConcurrency: Int = 0
  private val requestTimeNanosOnConcurrency: mutable.Map[Int, Long] = new mutable.HashMap().withDefaultValue(0)
  private var requestLastChangedNanos: Long = System.nanoTime()

  private var databaseCount: Int = 0
  private var databaseTimeNanos: Long = 0
  private var databaseConcurrency: Int = 0
  private val databaseTimeNanosOnConcurrency: mutable.Map[Int, Long] = new mutable.HashMap().withDefaultValue(0)
  private var databaseLastChangedNanos: Long = System.nanoTime()

  private var lastReportedNanos = System.nanoTime()

  // Report every second - this
  timers.startPeriodicTimer("tick", Tick, settings.reportPeriod)

  private def updateCommandState(): Unit = {
    val currentNanos = System.nanoTime()
    val sinceLastNanos = currentNanos - commandLastChangedNanos
    commandTimeNanosOnConcurrency.update(commandConcurrency, commandTimeNanosOnConcurrency(commandConcurrency) + sinceLastNanos)
    commandLastChangedNanos = currentNanos
  }

  private def updateRequestState(): Unit = {
    val currentNanos = System.nanoTime()
    val sinceLastNanos = currentNanos - requestLastChangedNanos
    requestTimeNanosOnConcurrency.update(requestConcurrency, requestTimeNanosOnConcurrency(requestConcurrency) + sinceLastNanos)
    requestLastChangedNanos = currentNanos
  }

  private def updateDatabaseState(): Unit = {
    val currentNanos = System.nanoTime()
    val sinceLastNanos = currentNanos - databaseLastChangedNanos
    databaseTimeNanosOnConcurrency.update(databaseConcurrency, databaseTimeNanosOnConcurrency(databaseConcurrency) + sinceLastNanos)
    databaseLastChangedNanos = currentNanos
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
    val average = if (totalTimeUsed > 0) {
      times.map {
        case (c, value) => c * toSeconds(value)
      }.sum / toSeconds(totalTimeUsed)
    } else 0.0

    math.max(average, 0.0)
  }

  override def receive: Receive = {
    case CommandSent =>
      updateCommandState()
      commandCount += 1
      commandConcurrency += 1

    case ReplyReceived(timeNanos) =>
      updateCommandState()
      commandConcurrency -= 1
      commandTimeNanos += timeNanos

    case RequestReceived =>
      updateRequestState()
      requestCount += 1
      requestConcurrency += 1

    case ResponseSent(timeNanos) =>
      updateRequestState()
      requestConcurrency -= 1
      requestTimeNanos += timeNanos

    case DatabaseOperationStarted =>
      updateDatabaseState()
      databaseConcurrency += 1

    case DatabaseOperationFinished(timeNanos) =>
      updateDatabaseState()
      databaseCount += 1
      databaseTimeNanos += timeNanos
      databaseConcurrency -= 1

    case Tick =>
      val currentTime = System.nanoTime()
      updateCommandState()
      updateRequestState()
      updateDatabaseState()
      val reportPeriodNanos = Math.max(currentTime - lastReportedNanos, 1)

      val avgCommandConcurrency = weightedAverage(commandTimeNanosOnConcurrency)
      val avgRequestConcurrency = weightedAverage(requestTimeNanosOnConcurrency)
      val avgDatabaseConcurrency = weightedAverage(databaseTimeNanosOnConcurrency)

      autoscaler ! AutoscalerMetrics(
        address = address,
        uniqueAddressLongId = uniqueAddressLongId,
        metricIntervalNanos = reportPeriodNanos,
        requestConcurrency = avgRequestConcurrency,
        requestTimeNanos = requestTimeNanos,
        requestCount = requestCount,
        userFunctionConcurrency = avgCommandConcurrency,
        userFunctionTimeNanos = commandTimeNanos,
        userFunctionCount = commandCount,
        databaseConcurrency = avgDatabaseConcurrency,
        databaseTimeNanos = databaseTimeNanos,
        databaseCount = databaseCount,
      )


      lastReportedNanos = currentTime
      commandCount = 0
      commandTimeNanosOnConcurrency.clear()
      commandTimeNanos = 0
      requestCount = 0
      requestTimeNanosOnConcurrency.clear()
      requestTimeNanos = 0
      databaseCount = 0
      databaseTimeNanosOnConcurrency.clear()
      databaseTimeNanos = 0
  }
}
