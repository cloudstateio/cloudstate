package io.cloudstate.proxy.streams

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.SourceShape
import akka.stream.scaladsl.{GraphDSL, Sink, Source}
import akka.testkit.TestKit
import org.scalatest.{AsyncWordSpecLike, Matchers}

import scala.collection.immutable
import scala.concurrent.Future

class MergeSequenceSpec extends TestKit(ActorSystem("MergeSequenceSpec")) with AsyncWordSpecLike with Matchers {

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

    "propagate errors" in recoverToSucceededIf[SpecificError] {
      mergeSources(
        Source(List(0L, 3L)),
        Source(List(1L, 2L)).flatMapConcat {
          case 1L => Source.single(1L)
          case 2L => Source.failed(SpecificError())
        }
      )
    }

  }

  private def merge(seqs: immutable.Seq[Long]*): Future[immutable.Seq[Long]] =
    mergeSources(seqs.map(Source(_)): _*)

  private def mergeSources(sources: Source[Long, NotUsed]*): Future[immutable.Seq[Long]] =
    Source
      .fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val merge = builder.add(new MergeSequence[Long](sources.size)(identity))
        sources.foreach { source =>
          source ~> merge
        }

        SourceShape(merge.out)
      })
      .runWith(Sink.seq)

  private case class SpecificError() extends Exception

}
