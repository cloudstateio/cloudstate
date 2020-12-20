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

package io.cloudstate.testkit.action

import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.Trailers
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import io.cloudstate.protocol.action.{
  ActionCommand,
  ActionProtocol,
  ActionProtocolClient,
  ActionProtocolHandler,
  ActionResponse
}
import io.cloudstate.testkit.InterceptService.InterceptorContext
import io.grpc.Status

import scala.concurrent.Future
import scala.util.{Failure, Success}

final class InterceptActionService(context: InterceptorContext) {
  import InterceptActionService._

  private val interceptor = new ActionInterceptor(context)

  def expectUnaryConnection(): UnaryConnection = context.probe.expectMsgType[UnaryConnection]
  def expectStreamedInConnection(): StreamedInConnection = context.probe.expectMsgType[StreamedInConnection]
  def expectStreamedOutConnection(): StreamedOutConnection = context.probe.expectMsgType[StreamedOutConnection]
  def expectStreamedConnection(): StreamedConnection = context.probe.expectMsgType[StreamedConnection]

  private val errorHandler: ActorSystem => PartialFunction[Throwable, Trailers] = _ => {
    case e: Exception => Trailers(Status.INTERNAL.augmentDescription(e.getMessage))
  }

  def handler: PartialFunction[HttpRequest, Future[HttpResponse]] =
    ActionProtocolHandler.partial(interceptor, eHandler = errorHandler)(context.system)

  def terminate(): Unit = interceptor.terminate()
}

object InterceptActionService {
  case object Complete
  final case class Error(cause: Throwable)

  final class ActionInterceptor(context: InterceptorContext) extends ActionProtocol {
    import context.system.dispatcher

    private val client = ActionProtocolClient(context.clientSettings)(context.system)

    override def handleUnary(in: ActionCommand): Future[ActionResponse] = {
      val connection = new UnaryConnection(context, in)
      context.probe.ref ! connection
      val response = client.handleUnary(in)
      response.onComplete {
        case Success(r) => connection.out.testActor ! r
        case Failure(e) => connection.out.testActor ! Error(e)
      }
      response
    }

    override def handleStreamedIn(in: Source[ActionCommand, NotUsed]): Future[ActionResponse] = {
      val connection = new StreamedInConnection(context)
      context.probe.ref ! connection
      val response = client.handleStreamedIn(in.alsoTo(connection.inSink))
      response.onComplete {
        case Success(r) => connection.out.testActor ! r
        case Failure(e) => connection.out.testActor ! Error(e)
      }
      response
    }

    override def handleStreamedOut(in: ActionCommand): Source[ActionResponse, NotUsed] = {
      val connection = new StreamedOutConnection(context, in)
      context.probe.ref ! connection
      val out = client.handleStreamedOut(in)
      out.alsoTo(connection.outSink)
    }

    override def handleStreamed(in: Source[ActionCommand, NotUsed]): Source[ActionResponse, NotUsed] = {
      val connection = new StreamedConnection(context)
      context.probe.ref ! connection
      val out = client.handleStreamed(in.alsoTo(connection.inSink))
      out.alsoTo(connection.outSink)
    }

    def terminate(): Unit = client.close()
  }

  def ignoreMetadata(command: ActionCommand): ActionCommand =
    command.copy(metadata = None)

  def ignoreMetadata(response: ActionResponse): ActionResponse =
    response.update(
      if (response.response.isReply) _.reply.optionalMetadata := None else _ => identity,
      if (response.response.isForward) _.forward.optionalMetadata := None else _ => identity,
      _.sideEffects.modify(_.map(_.update(_.optionalMetadata := None)))
    )

  final class UnaryConnection(context: InterceptorContext, val command: ActionCommand) {
    private[testkit] val out = TestProbe("UnaryConnectionOutProbe")(context.system)

    def expectResponse(): ActionResponse =
      out.expectMsgType[ActionResponse]

    def expectIncoming(expected: ActionCommand): UnaryConnection = {
      val received = ignoreMetadata(command)
      assert(received == expected, s"Unexpected unary action command: expected $expected, found $received")
      this
    }

    def expectOutgoing(expected: ActionResponse): UnaryConnection = {
      val received = ignoreMetadata(expectResponse())
      assert(received == expected, s"Unexpected unary action response: expected $expected, found $received")
      this
    }
  }

  final class StreamedInConnection(context: InterceptorContext) {
    private[this] val in = TestProbe("StreamedInConnectionIn")(context.system)
    private[testkit] val out = TestProbe("StreamedInConnectionOut")(context.system)

    private[testkit] def inSink: Sink[ActionCommand, NotUsed] = Sink.actorRef(in.ref, Complete, Error.apply)

    def expectResponse(): ActionResponse =
      out.expectMsgType[ActionResponse]

    def expectCommand(): ActionCommand =
      in.expectMsgType[ActionCommand]

    def expectIncoming(expected: ActionCommand): StreamedInConnection = {
      val received = ignoreMetadata(expectCommand())
      assert(received == expected, s"Unexpected streamed-in action command: expected $expected, found $received")
      this
    }

    def expectOutgoing(expected: ActionResponse): StreamedInConnection = {
      val received = ignoreMetadata(expectResponse())
      assert(received == expected, s"Unexpected unary action response: expected $expected, found $received")
      this
    }

    def expectInComplete(): StreamedInConnection = {
      in.expectMsg(Complete)
      this
    }
  }

  final class StreamedOutConnection(context: InterceptorContext, val command: ActionCommand) {
    private[testkit] val out = TestProbe("StreamedOutConnectionOut")(context.system)

    private[testkit] def outSink: Sink[ActionResponse, NotUsed] = Sink.actorRef(out.ref, Complete, Error.apply)

    def expectResponse(): ActionResponse =
      out.expectMsgType[ActionResponse]

    def expectIncoming(expected: ActionCommand): StreamedOutConnection = {
      val received = ignoreMetadata(command)
      assert(received == expected, s"Unexpected streamed-out action command: expected $expected, found $received")
      this
    }

    def expectOutgoing(expected: ActionResponse): StreamedOutConnection = {
      val received = ignoreMetadata(expectResponse())
      assert(received == expected, s"Unexpected unary action response: expected $expected, found $received")
      this
    }

    def expectOutComplete(): StreamedOutConnection = {
      out.expectMsg(Complete)
      this
    }
  }

  final class StreamedConnection(context: InterceptorContext) {
    private[this] val in = TestProbe("StreamedConnectionIn")(context.system)
    private[this] val out = TestProbe("StreamedConnectionOut")(context.system)

    private[testkit] def inSink: Sink[ActionCommand, NotUsed] = Sink.actorRef(in.ref, Complete, Error.apply)
    private[testkit] def outSink: Sink[ActionResponse, NotUsed] = Sink.actorRef(out.ref, Complete, Error.apply)

    def expectCommand(): ActionCommand =
      in.expectMsgType[ActionCommand]

    def expectResponse(): ActionResponse =
      out.expectMsgType[ActionResponse]

    def expectIncoming(expected: ActionCommand): StreamedConnection = {
      val received = ignoreMetadata(expectCommand())
      assert(received == expected, s"Unexpected streamed action command: expected $expected, found $received")
      this
    }

    def expectOutgoing(expected: ActionResponse): StreamedConnection = {
      val received = ignoreMetadata(expectResponse())
      assert(received == expected, s"Unexpected unary action response: expected $expected, found $received")
      this
    }

    def expectInComplete(): StreamedConnection = {
      in.expectMsg(Complete)
      this
    }

    def expectOutComplete(): StreamedConnection = {
      out.expectMsg(Complete)
      this
    }

    def expectComplete(): StreamedConnection = {
      expectInComplete()
      expectOutComplete()
    }
  }
}
