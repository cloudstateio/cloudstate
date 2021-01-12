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

package io.cloudstate.testkit.crdt

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import io.cloudstate.protocol.crdt._
import io.cloudstate.testkit.InterceptService.InterceptorContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag

final class InterceptCrdtService(context: InterceptorContext) {
  import InterceptCrdtService._

  private val interceptor = new CrdtInterceptor(context)

  def expectConnection(): Connection = context.probe.expectMsgType[Connection]

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    CrdtHandler.partial(interceptor)(context.system)

  def terminate(): Unit = interceptor.terminate()
}

object InterceptCrdtService {
  final class CrdtInterceptor(context: InterceptorContext) extends Crdt {
    private val client = CrdtClient(context.clientSettings)(context.system)

    override def handle(in: Source[CrdtStreamIn, NotUsed]): Source[CrdtStreamOut, NotUsed] = {
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

    private[this] val in = TestProbe("CrdtInProbe")(context.system)
    private[this] val out = TestProbe("CrdtOutProbe")(context.system)

    private[testkit] def inSink: Sink[CrdtStreamIn, NotUsed] = Sink.actorRef(in.ref, Complete, Error.apply)
    private[testkit] def outSink: Sink[CrdtStreamOut, NotUsed] = Sink.actorRef(out.ref, Complete, Error.apply)

    def expectIncoming(message: CrdtStreamIn.Message): Connection = {
      in.expectMsg(CrdtStreamIn(message))
      this
    }

    def expectOutgoing(message: CrdtStreamOut.Message): Connection = {
      out.expectMsg(CrdtStreamOut(message))
      this
    }

    def expectOutgoingMessage[T](implicit classTag: ClassTag[T]): T =
      expectOutgoingMessageClass(classTag.runtimeClass.asInstanceOf[Class[T]])

    def expectOutgoingMessageClass[T](messageClass: Class[T]): T = {
      val message = out.expectMsgType[CrdtStreamOut].message
      assert(messageClass.isInstance(message), s"expected message $messageClass, found ${message.getClass} ($message)")
      message.asInstanceOf[T]
    }

    def expectNoInteraction(timeout: FiniteDuration = 0.seconds): Connection = {
      in.expectNoMessage(timeout)
      out.expectNoMessage(timeout)
      this
    }

    def expectClosed(): Unit = {
      in.expectMsg(Complete)
      out.expectMsg(Complete)
    }

    def expectClosed(max: FiniteDuration): Unit = {
      in.expectMsg(max, Complete)
      out.expectMsg(max, Complete)
    }
  }
}
