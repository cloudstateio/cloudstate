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

import java.util.Optional
import java.util.concurrent.{CompletableFuture, CompletionStage, TimeUnit}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.javadsl.Source
import akka.stream.scaladsl.{JavaFlowSupport, Sink}
import cloudstate.javasupport.Actionspec
import cloudstate.javasupport.Actionspec.{In, Out}
import com.google.protobuf
import io.cloudstate.javasupport.{Metadata, ServiceCallFactory}
import io.cloudstate.javasupport.action.{
  Action,
  ActionContext,
  ActionHandler,
  ActionReply,
  CallHandler,
  MessageEnvelope,
  MessageReply
}
import io.cloudstate.javasupport.impl.AnySupport
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.compat.java8.FutureConverters._

class AnnotationBasedActionSupportSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  private implicit val sys = ActorSystem("AnnotationBasedActionSupportSpec")

  import sys.dispatcher

  override protected def afterAll(): Unit = {
    super.afterAll()
    sys.terminate()
  }

  private val anySupport = new AnySupport(Array(Actionspec.getDescriptor), this.getClass.getClassLoader)

  private object ctx extends ActionContext {
    override def metadata(): Metadata = Metadata.EMPTY.add("scope", "call")

    override def serviceCallFactory(): ServiceCallFactory = ???
  }

  private def create(handler: AnyRef): ActionHandler =
    new AnnotationBasedActionSupport(handler, anySupport, Actionspec.getDescriptor.findServiceByName("ActionSpec"))

  "Annotation based action support" should {

    "support invoking unary commands" when {
      def test(handler: AnyRef) = {
        val reply = create(handler)
          .handleUnary("Unary", createInEnvelope("in"), ctx)
          .toCompletableFuture
          .get(10, TimeUnit.SECONDS)
        assertIsOutReplyWithField(reply, "out: in")
      }

      def inToOut(in: In): Out =
        Out.newBuilder().setField("out: " + in.getField).build()

      "synchronous" in test(new {
        @CallHandler
        def unary(in: In): Out = inToOut(in)
      })

      "asynchronous" in test(new {
        @CallHandler
        def unary(in: In): CompletionStage[Out] = CompletableFuture.completedFuture(inToOut(in))
      })

      "in wrapped in envelope" in test(new {
        @CallHandler
        def unary(in: MessageEnvelope[In]): Out = {
          in.metadata().get("scope") should ===(Optional.of("message"))
          inToOut(in.payload())
        }
      })

      "synchronous out wrapped in envelope" in test(new {
        @CallHandler
        def unary(in: In): MessageEnvelope[Out] = MessageEnvelope.of(inToOut(in))
      })

      "asynchronous out wrapped in envelope" in test(new {
        @CallHandler
        def unary(in: In): CompletionStage[MessageEnvelope[Out]] =
          CompletableFuture.completedFuture(MessageEnvelope.of(inToOut(in)))
      })

      "synchronous out wrapped in reply" in test(new {
        @CallHandler
        def unary(in: In): ActionReply[Out] = ActionReply.message(inToOut(in))
      })

      "asynchronous out wrapped in reply" in test(new {
        @CallHandler
        def unary(in: In): CompletionStage[ActionReply[Out]] =
          CompletableFuture.completedFuture(ActionReply.message(inToOut(in)))
      })

      "with metadata parameter" in test(new {
        @CallHandler
        def unary(in: In, metadata: Metadata): Out = {
          metadata.get("scope") should ===(Optional.of("call"))
          inToOut(in)
        }
      })

      "with context parameter" in test(new {
        @CallHandler
        def unary(in: In, context: ActionContext): Out = inToOut(in)
      })

    }

    "support invoking streamed out commands" when {
      def test(handler: AnyRef) = {
        val replies = Await.result(
          create(handler)
            .handleStreamedOut("StreamedOut", createInEnvelope("in"), ctx)
            .asScala
            .runWith(Sink.seq),
          10.seconds
        )
        replies should have size 3
        replies.zipWithIndex.foreach {
          case (reply, idx) =>
            assertIsOutReplyWithField(reply, s"out ${idx + 1}: in")
        }
      }

      def inToOut(in: In): akka.stream.scaladsl.Source[Out, NotUsed] =
        akka.stream.scaladsl
          .Source(1 to 3)
          .map { idx =>
            Out.newBuilder().setField(s"out $idx: " + in.getField).build()
          }

      "source" in test(new {
        @CallHandler
        def streamedOut(in: In): Source[Out, NotUsed] = inToOut(in).asJava
      })

      "reactive streams publisher" in test(new {
        @CallHandler
        def streamedOut(in: In): org.reactivestreams.Publisher[Out] =
          inToOut(in).runWith(Sink.asPublisher(false))
      })

      "jdk publisher" in test(new {
        @CallHandler
        def streamedOut(in: In): java.util.concurrent.Flow.Publisher[Out] =
          inToOut(in).runWith(JavaFlowSupport.Sink.asPublisher(false))
      })

      "message envelope" in test(new {
        @CallHandler
        def streamedOut(in: MessageEnvelope[In]): Source[Out, NotUsed] = inToOut(in.payload()).asJava
      })

      "source wrapped in envelope" in test(new {
        @CallHandler
        def streamedOut(in: In): Source[MessageEnvelope[Out], NotUsed] =
          inToOut(in).map(MessageEnvelope.of(_)).asJava
      })

      "source wrapped in reply" in test(new {
        @CallHandler
        def streamedOut(in: In): Source[ActionReply[Out], NotUsed] =
          inToOut(in).map[ActionReply[Out]](ActionReply.message(_)).asJava
      })

      "with metadata parameter" in test(new {
        @CallHandler
        def streamedOut(in: In, metadata: Metadata): Source[Out, NotUsed] = {
          metadata.get("scope") should ===(Optional.of("call"))
          inToOut(in).asJava
        }
      })

      "with context parameter" in test(new {
        @CallHandler
        def streamedOut(in: In, metadata: Metadata): Source[Out, NotUsed] = inToOut(in).asJava
      })

    }

    "support invoking streamed in commands" when {
      def test(handler: AnyRef) = {
        val reply = create(handler)
          .handleStreamedIn(
            "StreamedIn",
            akka.stream.scaladsl
              .Source(1 to 3)
              .map(idx => createInEnvelope("in " + idx))
              .asJava,
            ctx
          )
          .toCompletableFuture
          .get(10, TimeUnit.SECONDS)

        assertIsOutReplyWithField(reply, "out: in 1, in 2, in 3")
      }

      def inToOut(in: akka.stream.scaladsl.Source[In, NotUsed]): Future[Out] =
        in.runWith(Sink.seq).map { ins =>
          Out.newBuilder().setField("out: " + ins.map(_.getField).mkString(", ")).build()
        }

      "source" in test(new {
        @CallHandler
        def streamedIn(in: Source[In, NotUsed]): CompletionStage[Out] = inToOut(in.asScala).toJava
      })

      "reactive streams publisher" in test(new {
        @CallHandler
        def streamedIn(in: org.reactivestreams.Publisher[In]): CompletionStage[Out] =
          inToOut(akka.stream.scaladsl.Source.fromPublisher(in)).toJava
      })

      "jdk publisher" in test(new {
        @CallHandler
        def streamedIn(in: java.util.concurrent.Flow.Publisher[In]): CompletionStage[Out] =
          inToOut(JavaFlowSupport.Source.fromPublisher(in)).toJava
      })

      "source wrapped in envelope" in test(new {
        @CallHandler
        def streamedIn(in: Source[MessageEnvelope[In], NotUsed]): CompletionStage[Out] =
          inToOut(in.asScala.map(_.payload)).toJava
      })

      "returns envelope" in test(new {
        @CallHandler
        def streamedIn(in: Source[In, NotUsed]): CompletionStage[MessageEnvelope[Out]] =
          inToOut(in.asScala).map(MessageEnvelope.of(_)).toJava
      })

      "returns reply" in test(new {
        @CallHandler
        def streamedIn(in: Source[In, NotUsed]): CompletionStage[ActionReply[Out]] =
          inToOut(in.asScala).map[ActionReply[Out]](ActionReply.message(_)).toJava
      })

      "with metadata parameter" in test(new {
        @CallHandler
        def streamedIn(in: Source[In, NotUsed], metadata: Metadata): CompletionStage[Out] = {
          metadata.get("scope") should ===(Optional.of("call"))
          inToOut(in.asScala).toJava
        }
      })

      "with context parameter" in test(new {
        @CallHandler
        def streamedIn(in: Source[In, NotUsed], context: ActionContext): CompletionStage[Out] =
          inToOut(in.asScala).toJava
      })

    }

    "support invoking streamed commands" when {
      def test(handler: AnyRef) = {
        val replies = Await.result(
          create(handler)
            .handleStreamed(
              "Streamed",
              akka.stream.scaladsl
                .Source(1 to 3)
                .map(idx => createInEnvelope("in " + idx))
                .asJava,
              ctx
            )
            .asScala
            .runWith(Sink.seq),
          10.seconds
        )

        replies should have size 3
        replies.zipWithIndex.foreach {
          case (reply, idx) =>
            assertIsOutReplyWithField(reply, s"out: in ${idx + 1}")
        }
      }

      def inToOut(stream: akka.stream.scaladsl.Source[In, NotUsed]): akka.stream.scaladsl.Source[Out, NotUsed] =
        stream.map { in =>
          Out.newBuilder().setField("out: " + in.getField).build()
        }

      "source in source out" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed]): Source[Out, NotUsed] = inToOut(in.asScala).asJava
      })

      "reactive streams publisher in source out" in test(new {
        @CallHandler
        def streamed(in: org.reactivestreams.Publisher[In]): Source[Out, NotUsed] =
          inToOut(akka.stream.scaladsl.Source.fromPublisher(in)).asJava
      })

      "source in reactive streams publisher out" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed]): org.reactivestreams.Publisher[Out] =
          inToOut(in.asScala).runWith(Sink.asPublisher(false))
      })

      "reactive streams publisher in reactive streams publisher out" in test(new {
        @CallHandler
        def streamed(in: org.reactivestreams.Publisher[In]): org.reactivestreams.Publisher[Out] =
          inToOut(akka.stream.scaladsl.Source.fromPublisher(in)).runWith(Sink.asPublisher(false))
      })

      "jdk publisher in source out" in test(new {
        @CallHandler
        def streamed(in: java.util.concurrent.Flow.Publisher[In]): Source[Out, NotUsed] =
          inToOut(JavaFlowSupport.Source.fromPublisher(in)).asJava
      })

      "source in jdk publisher out" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed]): java.util.concurrent.Flow.Publisher[Out] =
          inToOut(in.asScala).runWith(JavaFlowSupport.Sink.asPublisher(false))
      })

      "jdk publisher in jdk publisher out" in test(new {
        @CallHandler
        def streamed(in: java.util.concurrent.Flow.Publisher[In]): java.util.concurrent.Flow.Publisher[Out] =
          inToOut(JavaFlowSupport.Source.fromPublisher(in)).runWith(JavaFlowSupport.Sink.asPublisher(false))
      })

      "in wrapped in envelope" in test(new {
        @CallHandler
        def streamed(in: Source[MessageEnvelope[In], NotUsed]): Source[Out, NotUsed] =
          inToOut(in.asScala.map(_.payload)).asJava
      })

      "out wrapped in envelope" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed]): Source[MessageEnvelope[Out], NotUsed] =
          inToOut(in.asScala).map(MessageEnvelope.of(_)).asJava
      })

      "in and out wrapped in envelope" in test(new {
        @CallHandler
        def streamed(in: Source[MessageEnvelope[In], NotUsed]): Source[MessageEnvelope[Out], NotUsed] =
          inToOut(in.asScala.map(_.payload())).map(MessageEnvelope.of(_)).asJava
      })

      "out wrapped in reply" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed]): Source[ActionReply[Out], NotUsed] =
          inToOut(in.asScala).map[ActionReply[Out]](ActionReply.message(_)).asJava
      })

      "in wrapped in envelope out wrapped in reply" in test(new {
        @CallHandler
        def streamed(in: Source[MessageEnvelope[In], NotUsed]): Source[ActionReply[Out], NotUsed] =
          inToOut(in.asScala.map(_.payload())).map[ActionReply[Out]](ActionReply.message(_)).asJava
      })

      "with metadata parameter" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed], metadata: Metadata): Source[Out, NotUsed] = {
          metadata.get("scope") should ===(Optional.of("call"))
          inToOut(in.asScala).asJava
        }
      })

      "with context parameter" in test(new {
        @CallHandler
        def streamed(in: Source[In, NotUsed], context: ActionContext): Source[Out, NotUsed] =
          inToOut(in.asScala).asJava
      })

    }

  }

  private def createInEnvelope(field: String) =
    MessageEnvelope.of(
      protobuf.Any.pack(In.newBuilder().setField(field).build()),
      Metadata.EMPTY.add("scope", "message")
    )

  private def assertIsOutReplyWithField(reply: ActionReply[protobuf.Any], field: String) =
    reply match {
      case message: MessageReply[protobuf.Any] =>
        val out = message.payload().unpack(classOf[Out])
        out.getField should ===(field)
      case other =>
        fail(s"$reply is not a MessageReply")
    }

}
