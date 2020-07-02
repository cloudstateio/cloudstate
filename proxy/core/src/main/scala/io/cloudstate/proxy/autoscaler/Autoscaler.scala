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

package io.cloudstate.proxy.autoscaler

import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  ActorRefFactory,
  ActorSystem,
  AddressFromURIString,
  DeadLetterSuppression,
  Props,
  Timers
}
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.{DistributedData, LWWRegister, LWWRegisterKey}
import akka.cluster.{Cluster, Member, MemberStatus, UniqueAddress}

import scala.collection.immutable.Queue
import scala.concurrent.duration._

import io.cloudstate.proxy.autoscaler._

final case class AutoscalerSettings(
    /**
     * Whether autoscaling is enabled.
     */
    enabled: Boolean,
    /**
     * Target concurrency on user functions.
     */
    targetUserFunctionConcurrency: Int,
    /**
     * Target concurrency for requests.
     */
    targetRequestConcurrency: Int,
    /**
     * The sliding window period used to calculate the current concurrency for all nodes.
     */
    targetConcurrencyWindow: FiniteDuration,
    /**
     * While scaling up, concurrency can spike due to the time it takes for new nodes to join the cluster,
     * to warm up/jit, and rebalance etc. So we don't make any new decisions based on concurrency until this
     * deadline is met.
     */
    scaleUpStableDeadline: FiniteDuration,
    /**
     * While scaling down, concurrency can spike due to rebalancing, so we don't make any new decisions based
     * on concurrency until this deadline is met.
     */
    scaleDownStableDeadline: FiniteDuration,
    /**
     * During the scaling up/down stable deadline period, decisions to scale up further are made based on the
     * request rate. If it increases by more than this factor, then we scale the number of nodes to handle that
     * rate based on the original rate per node being handled before scale up/down.
     */
    requestRateThresholdFactor: Double,
    /**
     * The window used to determine whether the request rate has exceeded the threshold.
     */
    requestRateThresholdWindow: FiniteDuration,
    maxScaleFactor: Double,
    maxScaleAbsolute: Int,
    maxMembers: Int,
    /**
     * Autoscale tick period.
     */
    tickPeriod: FiniteDuration
)

object AutoscalerSettings {

  def apply(system: ActorSystem): AutoscalerSettings = {
    val autoscalerConfig = system.settings.config.getConfig("cloudstate.proxy.autoscaler")
    val targetConcurrency = autoscalerConfig.getConfig("target-concurrency")
    val requestRate = autoscalerConfig.getConfig("request-rate")

    AutoscalerSettings(
      enabled = autoscalerConfig.getBoolean("enabled"),
      targetUserFunctionConcurrency = targetConcurrency.getInt("user-function"),
      targetRequestConcurrency = targetConcurrency.getInt("request"),
      targetConcurrencyWindow = targetConcurrency.getDuration("window").toMillis.millis,
      scaleUpStableDeadline = autoscalerConfig.getDuration("scale-up-stable-deadline").toMillis.millis,
      scaleDownStableDeadline = autoscalerConfig.getDuration("scale-down-stable-deadline").toMillis.millis,
      requestRateThresholdFactor = requestRate.getDouble("threshold-factor"),
      requestRateThresholdWindow = requestRate.getDuration("window").toMillis.millis,
      maxScaleFactor = autoscalerConfig.getDouble("max-scale-factor"),
      maxScaleAbsolute = autoscalerConfig.getInt("max-scale"),
      maxMembers = autoscalerConfig.getInt("max-members"),
      tickPeriod = autoscalerConfig.getDuration("tick-period").toMillis.millis
    )
  }

}

object Autoscaler {

  final case class Sample(
      receivedNanos: Long,
      metrics: AutoscalerMetrics
  )

  private final case class Summary(
      clusterMembers: Int,
      requestConcurrency: Double,
      databaseConcurrency: Double,
      userFunctionConcurrency: Double,
      requestRate: Double,
      requestTimeMillis: Double,
      userFunctionTimeMillis: Double,
      databaseTimeMillis: Double
  )

  final case class Deployment(name: String, ready: Int, scale: Int, upgrading: Boolean)
  final case class Scale(name: String, scale: Int)

  case object Tick extends DeadLetterSuppression

  /**
   * This state is never gossipped, it's used as a placeholder for when we are waiting to have all
   * the state we need to make decisions.
   */
  case object WaitingForState extends AutoscalerState

  type ScalerFactory = (ActorRef, ActorRefFactory) => ActorRef

  def props(settings: AutoscalerSettings,
            scalerFactory: ScalerFactory,
            clusterMembershipFacade: ClusterMembershipFacade): Props =
    Props(new Autoscaler(settings, scalerFactory, clusterMembershipFacade))
}

/**
 * This state is gossipped via an LWW-Register CRDT. Most implementations are protobuf messages.
 */
trait AutoscalerState

class Autoscaler(settings: AutoscalerSettings,
                 scalerFactory: Autoscaler.ScalerFactory,
                 clusterMembershipFacade: ClusterMembershipFacade)
    extends Actor
    with Timers
    with ActorLogging {

  import Autoscaler._

  private[this] final val scaler = scalerFactory(self, context)

  private[this] final val ddata = DistributedData(context.system)
  import ddata.selfUniqueAddress
  private[this] final val StateKey = LWWRegisterKey[AutoscalerState]("autoscaler")
  private[this] final val EmptyState = LWWRegister.create[AutoscalerState](WaitingForState)

  private[this] final var stats = Map.empty[UniqueAddress, Queue[Sample]].withDefaultValue(Queue.empty)
  private[this] final var deployment: Option[Deployment] = None

  private[this] final var reportHeaders = 0

  timers.startPeriodicTimer("tick", Tick, settings.tickPeriod)
  ddata.replicator ! Get(StateKey, ReadMajority(timeout = 5.seconds))

  override def receive: Receive = waitingForState(WaitingForState)

  private def become(handler: Receive): Unit =
    context become (
      handleMetrics orElse handler orElse {
        case UpdateSuccess(_, _) =>
        // Ignore
        case failure: UpdateFailure[_] =>
          log.warning("Failure updating autoscaler state CRDT {}", failure)
        // It's no big deal, it just means a majority of nodes couldn't be updated in the given timeout.
        // But this one was, and maybe several others were too, and worst case, the next cluster singleton
        // to read it will get the wrong state, in which case it will make its own decision about its state
        // soon enough.
        case deploy: Deployment =>
          deployment = Some(deploy)
      }
    )

  private def checkInit(state: AutoscalerState) =
    deployment match {
      case None =>
      case Some(deploy) =>
        val haveMetricsFromAllNodes = clusterMembershipFacade.upMembers.forall(stats.contains)

        if (haveMetricsFromAllNodes) {

          state match {
            case WaitingForState =>
            // Do nothing, we don't have our own state yet

            case Stable(_) =>
              become(stable)
              self ! Tick

            case ScalingUp(desired, lastStableRequestRatePerNode, wallClockDeadline, _) =>
              become(
                scalingUp(desired,
                          lastStableRequestRatePerNode,
                          Deadline.now + (wallClockDeadline - System.currentTimeMillis()).millis)
              )
              self ! Tick

            case ScalingDown(desired, wallClockDeadline, _) =>
              become(scalingDown(desired, Deadline.now + (wallClockDeadline - System.currentTimeMillis()).millis))
              self ! Tick

            case Upgrading(desired, lastStableRequestRatePerNode, _) =>
              become(upgrading(desired, lastStableRequestRatePerNode))
              self ! Tick
          }
        }
    }

  private def waitingForState(state: AutoscalerState): Receive = {
    case metrics: AutoscalerMetrics =>
      handleMetrics(metrics)
      checkInit(state)

    // Results of ddata state read
    case NotFound(StateKey, _) =>
      // No ddata state, assume stable
      context.become(waitingForState(Stable()))
      checkInit(Stable())
    case GetFailure(StateKey, _) =>
      // We failed to read from a majority, fall back to local
      ddata.replicator ! Get(StateKey, ReadLocal)

    case success @ GetSuccess(StateKey, _) =>
      // We still need to wait for all the nodes in the cluster to report stats
      val newState = success.get(StateKey).value
      context.become(waitingForState(newState))
      checkInit(newState)

    case Tick =>
      checkInit(state)

    case deploy: Deployment =>
      deployment = Some(deploy)
      checkInit(state)
  }

  /**
   * In this state, we are stable, the number of nodes is appropriate to keep the target concurrency
   */
  private def becomeStable(): Unit = {
    updateState(Stable())
    become(stable)
  }

  private def stable: Receive = {
    case Tick =>
      val summary = summarize()

      val adjustedRequestConcurrency = summary.requestConcurrency - summary.databaseConcurrency
      val desiredForUserFunction = Math.max(
        1,
        Math
          .ceil((summary.userFunctionConcurrency * summary.clusterMembers) / settings.targetUserFunctionConcurrency)
          .toInt
      )
      val desiredForRequest = Math.max(
        1,
        Math.ceil((adjustedRequestConcurrency * summary.clusterMembers) / settings.targetRequestConcurrency).toInt
      )

      if (summary.userFunctionConcurrency > settings.targetUserFunctionConcurrency) {
        val desired = capScaling(desiredForUserFunction, summary.clusterMembers)

        log.info(
          "Scaling up from {} to {} because user function concurrency {} exceeds target {}",
          summary.clusterMembers,
          desired,
          summary.userFunctionConcurrency,
          settings.targetUserFunctionConcurrency
        )

        scaleUp(desired, summary.requestRate)

      } else if (adjustedRequestConcurrency > settings.targetRequestConcurrency) {
        val desired = capScaling(desiredForRequest, summary.clusterMembers)

        log.info(
          "Scaling up from {} to {} because adjusted request concurrency {} exceeds target {}",
          summary.clusterMembers,
          desired,
          adjustedRequestConcurrency,
          settings.targetRequestConcurrency
        )

        scaleUp(desired, summary.requestRate)

      } else if (desiredForUserFunction < summary.clusterMembers && desiredForRequest < summary.clusterMembers) {
        val desired = capScaling(Math.max(desiredForRequest, desiredForUserFunction), summary.clusterMembers)

        log.info(
          "Scaling down to {} because desired nodes for user function {} and desired nodes for request handling {} is below cluster members {}",
          desired,
          desiredForUserFunction,
          desiredForRequest,
          summary.clusterMembers
        )

        scaleDown(desired)
      } else if (deployment.exists(_.upgrading)) {

        log.info("Deployment is currently upgrading")
        becomeUpgrading(deployment.fold(0)(_.scale), summary.requestRate)
      }

  }

  /**
   * Cap scaling to the max scaling settings
   */
  private def capScaling(desired: Int, clusterMembers: Int): Int = {
    val changeInClusterMembers = Math.abs(desired - clusterMembers)
    val factorCappedChange = if (settings.maxScaleFactor > 0) {
      Math.min(changeInClusterMembers, Math.max(1, clusterMembers * settings.maxScaleFactor).toInt)
    } else changeInClusterMembers
    val cappedChanged = if (settings.maxScaleAbsolute > 0) {
      Math.min(factorCappedChange, settings.maxScaleAbsolute)
    } else factorCappedChange
    val cappedDesired =
      if (desired > clusterMembers) clusterMembers + cappedChanged
      else clusterMembers - cappedChanged

    Math.min(cappedDesired, settings.maxMembers)
  }

  /**
   * In this state, we are scaling up, and waiting for the scale up deadline to elapse before switching back to stable, or
   * scaling up further. In addition, if observed request rate exceeds the last stable request rate by the request rate
   * threshold, we may scale up more.
   */
  private def scaleUp(desired: Int, lastStableRequestRatePerNode: Double): Unit = {
    deployment.foreach { d =>
      scaler ! Scale(d.name, desired)
    }
    updateState(
      ScalingUp(desired,
                lastStableRequestRatePerNode,
                System.currentTimeMillis() + settings.scaleUpStableDeadline.toMillis)
    )
    become(scalingUp(desired, lastStableRequestRatePerNode, settings.scaleUpStableDeadline.fromNow))
  }

  private def scalingUp(desired: Int, lastStableRequestRatePerNode: Double, deadline: Deadline): Receive = {

    case Tick =>
      if (deadline.isOverdue()) {
        log.info("Scaling up to {} stable period over", desired)
        // Concurrency would have gone crazy during the scaling period, so expire most of the old data
        expireMetricsOlderThan(System.nanoTime(), settings.requestRateThresholdWindow.toNanos)
        becomeStable()
        stable(Tick)
      } else if (!checkRequestRateScaling(desired, lastStableRequestRatePerNode)) {
        deployment match {
          case Some(Deployment(name, _, scale, _)) if scale != desired =>
            scaler ! Scale(name, desired)
          case _ =>
        }
      }
  }

  private def scalingDown(desired: Int, deadline: Deadline): Receive = {

    case Tick =>
      if (deadline.isOverdue()) {
        log.info("Scaling down to {} stable period over", desired)
        // Concurrency would have gone crazy during the scaling period, so expire most of the old data
        expireMetricsOlderThan(System.nanoTime(), settings.requestRateThresholdWindow.toNanos)
        becomeStable()
        stable(Tick)
      } else {
        summarize()
      }
  }

  private def checkRequestRateScaling(currentDesired: Int, lastStableRequestRatePerNode: Double) = {
    val summary = summarize()

    if (summary.requestRate > lastStableRequestRatePerNode * settings.requestRateThresholdFactor &&
        (summary.userFunctionConcurrency > settings.targetUserFunctionConcurrency ||
        summary.requestConcurrency > settings.targetRequestConcurrency)) {

      val newDesired = capScaling(
        Math.ceil(summary.requestRate * summary.clusterMembers / lastStableRequestRatePerNode).toInt,
        summary.clusterMembers
      )

      if (newDesired > currentDesired) {
        log.info(
          "Scaling up to {} because request rate {} has exceeded last stable request rate {} by configured factor {}",
          newDesired,
          summary.requestRate,
          lastStableRequestRatePerNode,
          settings.requestRateThresholdFactor
        )
        scaleUp(newDesired, lastStableRequestRatePerNode)
      }
      true
    } else false
  }

  /**
   * Like scale up, except with a different configured deadline.
   */
  private def scaleDown(desired: Int): Unit = {
    deployment.foreach { d =>
      scaler ! Scale(d.name, desired)
    }
    updateState(ScalingDown(desired, System.currentTimeMillis() + settings.scaleDownStableDeadline.toMillis))
    become(scalingDown(desired, settings.scaleDownStableDeadline.fromNow))
  }

  /**
   * When upgrading, due to the shutting down and starting of nodes, concurrency can go crazy. So, while upgrading,
   * we scale based on request rate.
   */
  private def becomeUpgrading(desired: Int, lastStableRequestRatePerNode: Double): Unit = {
    updateState(Upgrading(desired, lastStableRequestRatePerNode))
    become(upgrading(desired, lastStableRequestRatePerNode))
  }

  private def upgrading(desired: Int, lastStableRequestRatePerNode: Double): Receive = {
    case Tick =>
      summarize()
      if (deployment.forall(_.upgrading == false)) {
        log.info("Deployment is upgraded")
        becomeStable()
        stable(Tick)
      }
  }

  private def handleMetrics: Receive = {
    case m: AutoscalerMetrics =>
      val AddressFromURIString(address) = m.address
      val uniqueAddress = UniqueAddress(address, m.uniqueAddressLongId)
      stats = stats.updated(uniqueAddress, stats(uniqueAddress).enqueue(Sample(System.nanoTime(), m)))
  }

  private def updateState(state: AutoscalerState): Unit =
    // We write majority to ensure this immediately gets written to a majority of replicas. This mostly ensures
    // if we immediately after this leave the cluster, and a cluster singleton comes up somewhere else, when it
    // does a read majority, it should see the write.
    ddata.replicator ! Update(StateKey, EmptyState, WriteMajority(5.seconds))(_.withValueOf(state))

  private def summarize(): Summary = {
    val summaryTime = System.nanoTime()
    val concurrencyWindowNanos = settings.targetConcurrencyWindow.toNanos
    val requestRateWindowNanos = settings.requestRateThresholdWindow.toNanos

    var totalConcurrencyNanos = 0L
    var weightedRequestConcurrencySum = 0d
    var weightedDatabaseConcurrencySum = 0d
    var weightedUserFunctionConcurrencySum = 0d

    var requestCount = 0
    var requestTimeNanos = 0L
    var userFunctionCount = 0
    var userFunctionTimeNanos = 0L
    var databaseCount = 0
    var databaseTimeNanos = 0L

    var requestRatePerSecond = 0

    val clusterMembers = clusterMembershipFacade.upMemberCount

    expireMetricsOlderThan(summaryTime, concurrencyWindowNanos)

    stats.foreach {
      case (_, samples) =>
        var addressNanos = 0L
        var addressRequestCount = 0

        // Now, accumulate sample values
        samples.foreach { sample =>
          val metric = sample.metrics
          val interval = metric.metricIntervalNanos
          totalConcurrencyNanos += interval
          weightedRequestConcurrencySum += interval * metric.requestConcurrency
          weightedDatabaseConcurrencySum += interval * metric.databaseConcurrency
          weightedUserFunctionConcurrencySum += interval * metric.userFunctionConcurrency

          if (summaryTime - sample.receivedNanos < requestRateWindowNanos) {
            addressNanos += interval
            requestTimeNanos += metric.requestTimeNanos
            addressRequestCount += metric.requestCount
            userFunctionTimeNanos += metric.userFunctionTimeNanos
            userFunctionCount += metric.userFunctionCount
            databaseTimeNanos += metric.databaseTimeNanos
            databaseCount += metric.databaseCount
          }
        }

        requestCount += addressRequestCount
        val requestRate =
          if (addressNanos == 0) 0 else (addressRequestCount.toDouble / addressNanos * 1000000000L).toInt
        requestRatePerSecond += requestRate
    }

    val summary = if (totalConcurrencyNanos == 0) {
      Summary(clusterMembers, 0, 0, 0, 0, 0, 0, 0)
    } else {
      val requestTimeMillis = if (requestCount == 0) 0 else requestTimeNanos.toDouble / requestCount / 1000000
      val userFunctionTimeMillis =
        if (userFunctionCount == 0) 0 else userFunctionTimeNanos.toDouble / userFunctionCount / 1000000
      val databaseTimeMillis = if (databaseCount == 0) 0 else databaseTimeNanos.toDouble / databaseCount / 1000000

      Summary(
        clusterMembers = clusterMembers,
        requestConcurrency = weightedRequestConcurrencySum / totalConcurrencyNanos,
        databaseConcurrency = weightedDatabaseConcurrencySum / totalConcurrencyNanos,
        userFunctionConcurrency = weightedUserFunctionConcurrencySum / totalConcurrencyNanos,
        requestRate = requestRatePerSecond,
        requestTimeMillis = requestTimeMillis,
        userFunctionTimeMillis = userFunctionTimeMillis,
        databaseTimeMillis = databaseTimeMillis
      )
    }
    logReport(summary)
    summary
  }

  private def expireMetricsOlderThan(currentTimeNanos: Long, olderThanNanos: Long): Unit =
    stats.foreach {
      case (address, samples) =>
        val currentSamples = samples.dropWhile(sample => currentTimeNanos - sample.receivedNanos > olderThanNanos)
        if (currentSamples.isEmpty) {
          stats -= address
        } else if (currentSamples.size != samples.size) {
          stats += (address -> currentSamples)
        }
    }

  private def logReport(summary: Summary): Unit =
    if (log.isDebugEnabled) {
      reportHeaders += 1
      if (reportHeaders % 10 == 1) {
        log.debug(
          "%8s %8s %8s %8s %8s %8s %8s %8s %8s %8s".format("Members",
                                                           "Scale",
                                                           "Ready",
                                                           "UF cncy",
                                                           "UF lat",
                                                           "Req cncy",
                                                           "Req lat",
                                                           "Req rate",
                                                           "DB cncy",
                                                           "DB lat")
        )
      }
      log.debug(
        "%8d %8d %8d %8.2f %8.1f %8.2f %8.1f %8.1f %8.2f %8.1f".format(
          summary.clusterMembers,
          deployment.fold(0)(_.scale),
          deployment.fold(0)(_.ready),
          summary.userFunctionConcurrency,
          summary.userFunctionTimeMillis,
          summary.requestConcurrency,
          summary.requestTimeMillis,
          summary.requestRate,
          summary.databaseConcurrency,
          summary.databaseTimeMillis
        )
      )
    }

}

/**
 * Facade over Akka cluster membership, so it can be substituted for unit testing purposes.
 */
trait ClusterMembershipFacade {
  def upMembers: Iterable[UniqueAddress]
  def upMemberCount: Int = upMembers.size
}

class ClusterMembershipFacadeImpl(cluster: Cluster) extends ClusterMembershipFacade {

  override def upMembers: Iterable[UniqueAddress] =
    cluster.state.members.collect {
      case member if memberIsUp(member) => member.uniqueAddress
    }

  override def upMemberCount: Int =
    cluster.state.members.count(memberIsUp)

  private def memberIsUp(member: Member): Boolean =
    member.status == MemberStatus.Up || member.status == MemberStatus.WeaklyUp
}
