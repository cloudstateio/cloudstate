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
import akka.testkit.TestProbe
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.crud.{Crud, CrudHandler, CrudStreamIn, CrudStreamOut}
import io.cloudstate.protocol.entity.EntitySpec
import io.cloudstate.testkit.TestService

import scala.concurrent.Future

class TestCrudService extends TestService {
  private val crud = new TestCrudService.TestCrud(system, probe)

  override protected def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    super.handler orElse CrudHandler.partial(crud)

  def expectConnection(): TestCrudService.Connection = probe.expectMsgType[TestCrudService.Connection]

  start()
}

object TestCrudService {
  def apply(): TestCrudService = new TestCrudService

  def entitySpec(service: ServiceDescription): EntitySpec =
    TestService.entitySpec(Crud.name, service)

  def entitySpec(descriptors: Seq[ServiceDescriptor]): EntitySpec =
    TestService.entitySpec(Crud.name, descriptors)

  final class TestCrud(system: ActorSystem, probe: TestProbe) extends Crud {
    override def handle(source: Source[CrudStreamIn, NotUsed]): Source[CrudStreamOut, NotUsed] = {
      val connection = new Connection(system, source)
      probe.ref ! connection
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
