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

package io.cloudstate.proxy.eventsourced

import akka.actor.ActorRef
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

import scala.collection.immutable
import scala.concurrent.Future

class DynamicLeastShardAllocationStrategy(rebalanceThreshold: Int,
                                          maxSimultaneousRebalance: Int,
                                          rebalanceNumber: Int,
                                          rebalanceFactor: Double)
    extends ShardAllocationStrategy
    with Serializable {

  def this(rebalanceThreshold: Int, maxSimultaneousRebalance: Int) =
    this(rebalanceThreshold, maxSimultaneousRebalance, rebalanceThreshold, 0.0)

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]
  ): Future[ActorRef] = {
    val (regionWithLeastShards, _) = currentShardAllocations.minBy { case (_, v) => v.size }
    Future.successful(regionWithLeastShards)
  }

  override def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                         rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] =
    if (rebalanceInProgress.size < maxSimultaneousRebalance) {
      val (_, leastShards) = currentShardAllocations.minBy { case (_, v) => v.size }
      val mostShards = currentShardAllocations
        .collect {
          case (_, v) => v.filterNot(s => rebalanceInProgress(s))
        }
        .maxBy(_.size)
      val difference = mostShards.size - leastShards.size
      if (difference > rebalanceThreshold) {

        val factoredRebalanceLimit = (rebalanceFactor, rebalanceNumber) match {
          // This condition is to maintain semantic backwards compatibility, from when rebalanceThreshold was also
          // the number of shards to move.
          case (0.0, 0) => rebalanceThreshold
          case (0.0, justAbsolute) => justAbsolute
          case (justFactor, 0) => math.max((justFactor * mostShards.size).round.toInt, 1)
          case (factor, absolute) => math.min(math.max((factor * mostShards.size).round.toInt, 1), absolute)
        }

        // The ideal number to rebalance to so these nodes have an even number of shards
        val evenRebalance = difference / 2

        val n =
          math.min(math.min(factoredRebalanceLimit, evenRebalance), maxSimultaneousRebalance - rebalanceInProgress.size)
        Future.successful(mostShards.sorted.take(n).toSet)
      } else
        emptyRebalanceResult
    } else emptyRebalanceResult

  private[this] final val emptyRebalanceResult = Future.successful(Set.empty[ShardId])
}
