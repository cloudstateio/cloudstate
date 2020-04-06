package io.cloudstate.proxy.streams

import akka.stream.{Attributes, Inlet, Outlet, UniformFanInShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.collection.mutable

object MergeSequence {

  private case class Pushed[T](in: Inlet[T], sequence: Long, elem: T)

  private implicit def ordering[T]: Ordering[Pushed[T]] = Ordering.by[Pushed[T], Long](_.sequence).reverse
}

class MergeSequence[T](inputPorts: Int)(extractSequence: T => Long) extends GraphStage[UniformFanInShape[T, T]] {
  private val in: IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i => Inlet[T]("MergeSequence.in" + i))
  private val out: Outlet[T] = Outlet("MergeSequence.out")
  override val shape: UniformFanInShape[T, T] = UniformFanInShape(out, in: _*)

  import MergeSequence._

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {
      private var nextSequence = 0L
      private val available = mutable.PriorityQueue.empty[Pushed[T]]
      private var complete = 0

      setHandler(out, this)

      in.zipWithIndex.foreach {
        case (inPort, idx) =>
          setHandler(
            inPort,
            new InHandler {
              override def onPush(): Unit = {
                val elem = grab(inPort)
                val sequence = extractSequence(elem)
                if (sequence < nextSequence) {
                  failStage(
                    new IllegalStateException(s"Sequence regression from $nextSequence to $sequence on port $idx")
                  )
                } else if (sequence == nextSequence && isAvailable(out)) {
                  push(out, elem)
                  tryPull(inPort)
                  nextSequence += 1
                } else {
                  available.enqueue(Pushed(inPort, sequence, elem))
                  detectMissedSequence()
                }
              }

              override def onUpstreamFinish(): Unit = {
                complete += 1
                if (complete == inputPorts && available.isEmpty) {
                  completeStage()
                } else {
                  detectMissedSequence()
                }
              }
            }
          )
      }

      def onPull(): Unit =
        if (available.nonEmpty && available.head.sequence == nextSequence) {
          val pushed = available.dequeue()
          push(out, pushed.elem)
          if (complete == inputPorts && available.isEmpty) {
            completeStage()
          } else {
            if (available.nonEmpty && available.head.sequence == nextSequence) {
              failStage(
                new IllegalStateException(
                  s"Duplicate sequence $nextSequence on ports ${pushed.in} and ${available.head.in}"
                )
              )
            }
            tryPull(pushed.in)
            nextSequence += 1
          }
        } else {
          detectMissedSequence()
        }

      private def detectMissedSequence(): Unit =
        // Cheap to calculate, but doesn't give the right answer, because there might be input ports
        // that are both complete and still have one last buffered element.
        if (isAvailable(out) && available.size + complete >= inputPorts) {
          // So in the event that this was true we count the number of ports that we have elements buffered for that
          // are not yet closed, and add that to the complete ones, to see if we're in a dead lock.
          if (available.count(pushed => !isClosed(pushed.in)) + complete == inputPorts) {
            failStage(
              new IllegalStateException(
                s"Expected sequence $nextSequence, but all input ports have pushed or are complete, " +
                "but none have pushed the next sequence number. Pushed sequences: " +
                available.toVector.map(p => s"${p.in}: ${p.sequence}").mkString(", ")
              )
            )
          }
        }

      override def preStart(): Unit =
        in.foreach(pull)
    }

  override def toString: String = s"MergeSequence($inputPorts)"
}
