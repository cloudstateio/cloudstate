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

package io.cloudstate.javasupport.impl.action

import java.util.concurrent.{CompletableFuture, CompletionStage}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.javadsl.Source
import akka.stream.scaladsl.Sink
import cloudstate.javasupport.Actionspec
import cloudstate.javasupport.Actionspec.{In, Out}
import com.google.protobuf
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.{Context, ServiceCallFactory}
import io.cloudstate.javasupport.action.{ActionContext, ActionHandler, ActionReply, Effect, MessageEnvelope}
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedServiceCallFactory}
import io.cloudstate.protocol.action.{ActionCommand, ActionProtocol, ActionResponse}
import io.cloudstate.protocol.entity.{Forward, Reply}
import org.scalatest.{BeforeAndAfterAll, Inside, Matchers, OptionValues, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.compat.java8.FutureConverters._

class ActionServiceSpec extends WordSpec with Matchers with BeforeAndAfterAll with Inside with OptionValues {

  private implicit val system = ActorSystem("ActionServiceSpec")

  import system.dispatcher

  private val serviceDescriptor =
    cloudstate.javasupport.Actionspec.getDescriptor.findServiceByName("ActionSpec")
  private val serviceName = serviceDescriptor.getFullName

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.terminate()
  }

  def create(handler: ActionHandler): ActionProtocol = {
    val service = new ActionService(
      handler,
      serviceDescriptor,
      new AnySupport(Array(Actionspec.getDescriptor), this.getClass.getClassLoader)
    )

    val services = Map(serviceName -> service)
    val scf = new ResolvedServiceCallFactory(services)

    new ActionProtocolImpl(system, services, new Context() {
      override def serviceCallFactory(): ServiceCallFactory = scf
    })
  }

  "The action service" should {
    "invoke unary commands" in {
      val service = create(new AbstractHandler {
        override def handleUnary(commandName: String,
                                 message: MessageEnvelope[protobuf.Any],
                                 context: ActionContext): CompletionStage[ActionReply[protobuf.Any]] =
          CompletableFuture.completedFuture(createOutReply("out: " + extractInField(message)))
      })

      val reply = Await.result(service.handleUnary(
                                 ActionCommand(serviceName, "Unary", createInPayload("in"))
                               ),
                               10.seconds)

      inside(reply.response) {
        case ActionResponse.Response.Reply(Reply(payload, _, _)) =>
          extractOutField(payload) should ===("out: in")
      }
    }

    "invoke streamed in commands" in {
      val service = create(new AbstractHandler {
        override def handleStreamedIn(commandName: String,
                                      stream: Source[MessageEnvelope[protobuf.Any], NotUsed],
                                      context: ActionContext): CompletionStage[ActionReply[protobuf.Any]] =
          stream.asScala
            .map(extractInField)
            .runWith(Sink.seq)
            .map(ins => createOutReply("out: " + ins.mkString(", ")))
            .toJava
      })

      val reply = Await.result(
        service.handleStreamedIn(
          akka.stream.scaladsl.Source
            .single(ActionCommand(serviceName, "StreamedIn"))
            .concat(
              akka.stream.scaladsl.Source(1 to 3).map(idx => ActionCommand(payload = createInPayload(s"in $idx")))
            )
        ),
        10.seconds
      )

      inside(reply.response) {
        case ActionResponse.Response.Reply(Reply(payload, _, _)) =>
          extractOutField(payload) should ===("out: in 1, in 2, in 3")
      }
    }

    "invoke streamed out commands" in {
      val service = create(new AbstractHandler {
        override def handleStreamedOut(commandName: String,
                                       message: MessageEnvelope[protobuf.Any],
                                       context: ActionContext): Source[ActionReply[protobuf.Any], NotUsed] = {
          val in = extractInField(message)
          akka.stream.scaladsl.Source(1 to 3).map(idx => createOutReply(s"out $idx: $in")).asJava
        }
      })

      val replies = Await.result(service
                                   .handleStreamedOut(
                                     ActionCommand(serviceName, "Unary", createInPayload("in"))
                                   )
                                   .runWith(Sink.seq),
                                 10.seconds)

      replies.zipWithIndex.foreach {
        case (reply, idx) =>
          inside(reply.response) {
            case ActionResponse.Response.Reply(Reply(payload, _, _)) =>
              extractOutField(payload) should ===(s"out ${idx + 1}: in")
          }
      }
    }

    "invoke streamed commands" in {
      val service = create(new AbstractHandler {
        override def handleStreamed(commandName: String,
                                    stream: Source[MessageEnvelope[protobuf.Any], NotUsed],
                                    context: ActionContext): Source[ActionReply[protobuf.Any], NotUsed] =
          stream.asScala
            .map(extractInField)
            .map(in => createOutReply(s"out: $in"))
            .asJava
      })

      val replies = Await.result(
        service
          .handleStreamed(
            akka.stream.scaladsl.Source
              .single(ActionCommand(serviceName, "StreamedIn"))
              .concat(
                akka.stream.scaladsl.Source(1 to 3).map(idx => ActionCommand(payload = createInPayload(s"in $idx")))
              )
          )
          .runWith(Sink.seq),
        10.seconds
      )

      replies.zipWithIndex.foreach {
        case (reply, idx) =>
          inside(reply.response) {
            case ActionResponse.Response.Reply(Reply(payload, _, _)) =>
              extractOutField(payload) should ===(s"out: in ${idx + 1}")
          }
      }
    }

  }

  private def createOutAny(field: String) =
    protobuf.Any.pack(Out.newBuilder().setField(field).build())

  private def createOutReply(field: String): ActionReply[protobuf.Any] =
    ActionReply.message(createOutAny(field))

  private def extractInField(message: MessageEnvelope[protobuf.Any]) =
    message.payload().unpack(classOf[In]).getField

  private def createInPayload(field: String) =
    Some(ScalaPbAny.fromJavaProto(protobuf.Any.pack(In.newBuilder().setField(field).build())))

  private def extractOutField(payload: Option[ScalaPbAny]) =
    ScalaPbAny.toJavaProto(payload.value).unpack(classOf[Out]).getField

  private trait AbstractHandler extends ActionHandler {
    override def handleUnary(commandName: String,
                             message: MessageEnvelope[protobuf.Any],
                             context: ActionContext): CompletionStage[ActionReply[protobuf.Any]] = ???

    override def handleStreamedOut(commandName: String,
                                   message: MessageEnvelope[protobuf.Any],
                                   context: ActionContext): Source[ActionReply[protobuf.Any], NotUsed] = ???

    override def handleStreamedIn(commandName: String,
                                  stream: Source[MessageEnvelope[protobuf.Any], NotUsed],
                                  context: ActionContext): CompletionStage[ActionReply[protobuf.Any]] = ???

    override def handleStreamed(commandName: String,
                                stream: Source[MessageEnvelope[protobuf.Any], NotUsed],
                                context: ActionContext): Source[ActionReply[protobuf.Any], NotUsed] = ???
  }

}
