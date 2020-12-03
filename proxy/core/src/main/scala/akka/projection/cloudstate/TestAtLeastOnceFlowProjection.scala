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

package akka.projection.cloudstate

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.{ProjectionContext, ProjectionId, StatusObserver}
import akka.projection.internal.{
  AtLeastOnce,
  FlowHandlerStrategy,
  InternalProjection,
  NoopStatusObserver,
  RestartBackoffSettings,
  SettingsImpl,
  SingleHandlerStrategy
}
import akka.projection.scaladsl.{AtLeastOnceFlowProjection, Handler, SourceProvider}
import akka.projection.testkit.internal.{TestInMemoryOffsetStoreImpl, TestProjectionImpl}
import akka.projection.testkit.scaladsl.TestProjection
import akka.stream.scaladsl.FlowWithContext

import scala.concurrent.duration.FiniteDuration

/**
 * This exists because we need TestProjection to implement AtLeastOnceFlowProjection
 * (https://github.com/akka/akka-projection/issues/477), and the only way we can implement
 * it is if we are in the akka package.
 */
class TestAtLeastOnceFlowProjection[Offset, Envelope] private (delegate: TestProjectionImpl[Offset, Envelope])
    extends AtLeastOnceFlowProjection[Offset, Envelope]
    with SettingsImpl[TestAtLeastOnceFlowProjection[Offset, Envelope]]
    with InternalProjection {

  override def withStatusObserver(observer: StatusObserver[Envelope]): TestAtLeastOnceFlowProjection[Offset, Envelope] =
    new TestAtLeastOnceFlowProjection(delegate.withStatusObserver(observer))

  override def withRestartBackoffSettings(
      restartBackoff: RestartBackoffSettings
  ): TestAtLeastOnceFlowProjection[Offset, Envelope] =
    new TestAtLeastOnceFlowProjection(delegate.withRestartBackoffSettings(restartBackoff))

  override def withSaveOffset(afterEnvelopes: Int,
                              afterDuration: FiniteDuration): TestAtLeastOnceFlowProjection[Offset, Envelope] =
    new TestAtLeastOnceFlowProjection(delegate.withSaveOffset(afterEnvelopes, afterDuration))

  override def withGroup(groupAfterEnvelopes: Int,
                         groupAfterDuration: FiniteDuration): TestAtLeastOnceFlowProjection[Offset, Envelope] =
    new TestAtLeastOnceFlowProjection(delegate.withGroup(groupAfterEnvelopes, groupAfterDuration))

  override def projectionId: ProjectionId = delegate.projectionId

  override def statusObserver: StatusObserver[Envelope] = delegate.statusObserver

  override private[projection] def mappedSource()(implicit system: ActorSystem[_]) = delegate.mappedSource()

  override private[projection] def actorHandlerInit[T] = delegate.actorHandlerInit

  override private[projection] def run()(implicit system: ActorSystem[_]) = delegate.run()

  override private[projection] def offsetStrategy = delegate.offsetStrategy
}

object TestAtLeastOnceFlowProjection {
  def apply[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      flow: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _]
  ): TestAtLeastOnceFlowProjection[Offset, Envelope] =
    new TestAtLeastOnceFlowProjection(
      new TestProjectionImpl(projectionId = projectionId,
                             sourceProvider = sourceProvider,
                             handlerStrategy = FlowHandlerStrategy(flow),
                             offsetStrategy = AtLeastOnce(afterEnvelopes = Some(1)),
                             statusObserver = NoopStatusObserver,
                             offsetStoreFactory = () => new TestInMemoryOffsetStoreImpl[Offset](),
                             startOffset = None)
    )
}
