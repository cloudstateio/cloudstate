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

package io.cloudstate.testkit.eventsourced

import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.ServiceDescription
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.testkit.TestService.TestServiceContext
import io.cloudstate.testkit.discovery.TestEntityDiscoveryService
import scala.concurrent.Future

final class TestEventSourcedService(context: TestServiceContext) {
  import TestEventSourcedService._

  private val testEventSourced = new TestEventSourced(context)

  def expectConnection(): Connection = context.probe.expectMsgType[Connection]

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    EventSourcedHandler.partial(testEventSourced)(context.system)
}

object TestEventSourcedService {
  def entitySpec(service: ServiceDescription): EntitySpec =
    TestEntityDiscoveryService.entitySpec(EventSourced.name, service)

  def entitySpec(descriptors: Seq[ServiceDescriptor]): EntitySpec =
    TestEntityDiscoveryService.entitySpec(EventSourced.name, descriptors)

  final class TestEventSourced(context: TestServiceContext) extends EventSourced {
    override def handle(source: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = {
      val connection = new Connection(context.system, source)
      context.probe.ref ! connection
      connection.outSource
    }
  }

  final class Connection(system: ActorSystem, source: Source[EventSourcedStreamIn, NotUsed]) {
    private implicit val actorSystem: ActorSystem = system
    private val in = source.runWith(TestSink.probe[EventSourcedStreamIn])
    private val out = TestPublisher.probe[EventSourcedStreamOut]()

    in.ensureSubscription()

    private[testkit] def outSource: Source[EventSourcedStreamOut, NotUsed] = Source.fromPublisher(out)

    def expect(message: EventSourcedStreamIn.Message): Unit =
      in.request(1).expectNext(EventSourcedStreamIn(message))

    def send(message: EventSourcedStreamOut.Message): Unit =
      out.sendNext(EventSourcedStreamOut(message))

    def sendError(error: Throwable): Unit =
      out.sendError(error)

    def expectClosed(): Unit = {
      in.expectComplete()
      close()
    }

    def close(): Unit =
      out.sendComplete()
  }
}
