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

package io.cloudstate.proxy.eventing

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.cloudstate.TestAtLeastOnceFlowProjection
import akka.projection.{ProjectionContext, ProjectionId}
import akka.projection.scaladsl.{AtLeastOnceFlowProjection, SourceProvider}
import akka.stream.scaladsl.FlowWithContext

import scala.concurrent.Future

trait ProjectionSupport {
  def create[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      flow: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _]
  ): AtLeastOnceFlowProjection[Offset, Envelope]

  def prepare(): Future[Done] = Future.successful(Done)
}

class InMemoryProjectionSupport(system: ActorSystem[_]) extends ProjectionSupport {
  override def create[Offset, Envelope](
      projectionId: ProjectionId,
      sourceProvider: SourceProvider[Offset, Envelope],
      flow: FlowWithContext[Envelope, ProjectionContext, Done, ProjectionContext, _]
  ): AtLeastOnceFlowProjection[Offset, Envelope] =
    TestAtLeastOnceFlowProjection(projectionId, sourceProvider, flow)
}
