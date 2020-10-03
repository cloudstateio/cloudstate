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

package io.cloudstate.testkit.crud

import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.ServiceDescription
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.scaladsl.TestSink
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.crud.{Crud, CrudHandler, CrudStreamIn, CrudStreamOut}
import io.cloudstate.protocol.entity.EntitySpec
import io.cloudstate.testkit.TestService.TestServiceContext
import io.cloudstate.testkit.discovery.TestEntityDiscoveryService

import scala.concurrent.Future

class TestCrudService(context: TestServiceContext) {
  import TestCrudService._

  private val testCrud = new TestCrud(context)

  def expectConnection(): Connection = context.probe.expectMsgType[Connection]

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    CrudHandler.partial(testCrud)(context.system)
}

object TestCrudService {
  def entitySpec(service: ServiceDescription): EntitySpec =
    TestEntityDiscoveryService.entitySpec(Crud.name, service)

  def entitySpec(descriptors: Seq[ServiceDescriptor]): EntitySpec =
    TestEntityDiscoveryService.entitySpec(Crud.name, descriptors)

  final class TestCrud(context: TestServiceContext) extends Crud {
    override def handle(source: Source[CrudStreamIn, NotUsed]): Source[CrudStreamOut, NotUsed] = {
      val connection = new Connection(context.system, source)
      context.probe.ref ! connection
      connection.outSource
    }
  }

  final class Connection(system: ActorSystem, source: Source[CrudStreamIn, NotUsed]) {
    private implicit val actorSystem: ActorSystem = system
    private val in = source.runWith(TestSink.probe[CrudStreamIn])
    private val out = TestPublisher.probe[CrudStreamOut]()

    in.ensureSubscription()

    private[testkit] def outSource: Source[CrudStreamOut, NotUsed] = Source.fromPublisher(out)

    def expect(message: CrudStreamIn.Message): Unit =
      in.request(1).expectNext(CrudStreamIn(message))

    def send(message: CrudStreamOut.Message): Unit =
      out.sendNext(CrudStreamOut(message))

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
