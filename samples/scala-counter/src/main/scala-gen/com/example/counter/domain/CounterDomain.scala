/*
* Pseudo-Generated
* */

package com.example.counter.domain

object CounterDomain {

  trait CounterEvent {

  }

  object CounterEvent {
    case class CounterInitialized(initialValue: Int) extends CounterEvent
    case class CounterStarted(maxValue: Int) extends CounterEvent
    case class CounterIncremented(amount: Int) extends CounterEvent
    case class CounterStopped() extends CounterEvent
  }

  trait CounterState

  object CounterState {
    def unhandled = throw new UnsupportedOperationException("Unhandled event")

    def activeCounter(value: Int, maxValue: Int): ActiveCounter = ActiveCounter(value, maxValue)

    def uninitializedCounter(): UninitializedCounter = UninitializedCounter()

    def stoppedCounter(value: Int): StoppedCounter = StoppedCounter(value)

    case class UninitializedCounter() extends CounterState

    case class ActiveCounter(value: Int, maxValue: Int) extends CounterState

    case class StoppedCounter(value: Int) extends CounterState


  }
}
