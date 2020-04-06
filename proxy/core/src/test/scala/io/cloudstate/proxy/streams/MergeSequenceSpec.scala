package io.cloudstate.proxy.streams

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, SourceShape}
import akka.stream.scaladsl.{GraphDSL, Sink, Source}
import akka.testkit.TestKit
import org.scalatest.{AsyncWordSpecLike, Matchers}

import scala.collection.immutable
import scala.concurrent.Future

class MergeSequenceSpec extends TestKit(ActorSystem("MergeSequenceSpec")) with AsyncWordSpecLike with Matchers {

  private implicit val mat = ActorMaterializer()

  "MergeSequence" should {
    "merge interleaved streams" in {
      merge(
        List(0L, 4L, 8L, 9L, 11L),
        List(1L, 3L, 6L, 10L, 13L),
        List(2L, 5L, 7L, 12L)
      ).map { result =>
        result should contain theSameElementsInOrderAs (0L to 13L)
      }
    }

    "merge non interleaved streams" in {
      merge(
        List(5L, 6L, 7L, 8L, 9L),
        List(0L, 1L, 2L, 3L, 4L),
        List(10L, 11L, 12L, 13L)
      ).map { result =>
        result should contain theSameElementsInOrderAs (0L to 13L)
      }
    }

    "fail on duplicate sequence numbers" in recoverToSucceededIf[IllegalStateException] {
      merge(
        List(0L, 1L, 2L),
        List(2L)
      )
    }

    "fail on missing sequence numbers" in recoverToSucceededIf[IllegalStateException] {
      merge(
        List(0L, 4L, 8L, 9L, 11L),
        List(1L, 3L, 10L, 13L),
        List(2L, 5L, 7L, 12L)
      )
    }

    "fail on missing sequence numbers if some streams have completed" in recoverToSucceededIf[IllegalStateException] {
      merge(
        List(0L, 4L, 8L, 9L, 11L),
        List(1L, 3L, 6L, 10L, 13L, 15L),
        List(2L, 5L, 7L, 12L)
      )
    }

    "fail on sequence in a single stream" in recoverToSucceededIf[IllegalStateException] {
      merge(
        List(0L, 4L, 8L, 7L, 9L, 11L),
        List(1L, 3L, 6L, 10L, 13L),
        List(2L, 5L, 7L, 12L)
      )
    }

  }

  private def merge(seqs: immutable.Seq[Long]*): Future[immutable.Seq[Long]] =
    Source
      .fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val merge = builder.add(new MergeSequence[Long](seqs.size)(identity))
        seqs.foreach { seq =>
          Source(seq) ~> merge
        }

        SourceShape(merge.out)
      })
      .runWith(Sink.seq)

}
