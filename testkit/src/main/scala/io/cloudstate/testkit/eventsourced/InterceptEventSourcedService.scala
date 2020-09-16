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
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.testkit.InterceptService.InterceptorContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag

final class InterceptEventSourcedService(context: InterceptorContext) {
  import InterceptEventSourcedService._

  private val interceptor = new EventSourcedInterceptor(context)

  def expectConnection(): Connection = context.probe.expectMsgType[Connection]

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    EventSourcedHandler.partial(interceptor)(context.system)

  def terminate(): Unit = interceptor.terminate()
}

object InterceptEventSourcedService {
  final class EventSourcedInterceptor(context: InterceptorContext) extends EventSourced {
    private val client = EventSourcedClient(context.clientSettings)(context.system)

    override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = {
      val connection = new Connection(context)
      context.probe.ref ! connection
      client.handle(in.alsoTo(connection.inSink)).alsoTo(connection.outSink)
    }

    def terminate(): Unit = client.close()
  }

  object Connection {
    case object Complete
    final case class Error(cause: Throwable)
  }

  final class Connection(context: InterceptorContext) {
    import Connection._

    private[this] val in = TestProbe("EventSourcedInProbe")(context.system)
    private[this] val out = TestProbe("EventSourcedOutProbe")(context.system)

    private[testkit] def inSink: Sink[EventSourcedStreamIn, NotUsed] = Sink.actorRef(in.ref, Complete, Error.apply)
    private[testkit] def outSink: Sink[EventSourcedStreamOut, NotUsed] = Sink.actorRef(out.ref, Complete, Error.apply)

    def expectClient(message: EventSourcedStreamIn.Message): Connection = {
      in.expectMsg(EventSourcedStreamIn(message))
      this
    }

    def expectService(message: EventSourcedStreamOut.Message): Connection = {
      out.expectMsg(EventSourcedStreamOut(message))
      this
    }

    def expectServiceMessage[T](implicit classTag: ClassTag[T]): T =
      expectServiceMessageClass(classTag.runtimeClass.asInstanceOf[Class[T]])

    def expectServiceMessageClass[T](messageClass: Class[T]): T = {
      val message = out.expectMsgType[EventSourcedStreamOut].message
      assert(messageClass.isInstance(message), s"expected message $messageClass, found ${message.getClass} ($message)")
      message.asInstanceOf[T]
    }

    def expectNoInteraction(timeout: FiniteDuration = 0.seconds): Connection = {
      in.expectNoMessage(timeout)
      out.expectNoMessage(timeout)
      this
    }
  }
}
