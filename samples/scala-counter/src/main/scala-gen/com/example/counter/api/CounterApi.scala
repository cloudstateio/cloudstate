/*
* Pseudo-Generated
* */

package com.example.counter.api

trait CounterApi

object CounterApi {

  trait CounterCommandRequest

  trait CounterCommandResponse

  trait Counter

  object CounterCommandRequest {

    case class InitializeRequest(counterId: String, initialValue: Int) extends CounterCommandRequest

    case class StartRequest(counterId: String, maxValue: Int) extends CounterCommandRequest

    case class StopRequest(counterId: String) extends CounterCommandRequest

    case class IncrementRequest(counterId: String, amount: Int) extends CounterCommandRequest

    case class RestartRequest(counterId: String, maxValue: Int) extends CounterCommandRequest

    case class GetCounterRequest(counterId: String) extends CounterCommandRequest

    object InitializeRequest {}

    object StartRequest {}

    object StopRequest {}

    object IncrementRequest {}

    object RestartRequest {}

    object GetCounterRequest {}

  }

  object CounterCommandResponse {

    case class InitializeResponse(counter: Counter) extends CounterCommandResponse

    case class StartResponse(counter: Counter) extends CounterCommandResponse {
    }

    case class IncrementResponse(counter: Counter) extends CounterCommandResponse {
    }

    case class StopResponse(counter: Counter) extends CounterCommandResponse {
    }

    case class RestartResponse(counter: Counter) extends CounterCommandResponse

    case class GetCounterResponse(counter: Counter) extends CounterCommandResponse {
    }

    object InitializeResponse {
    }

    object StartResponse {}

    object IncrementResponse {}

    object StopResponse {}

    object RestartResponse {}

    object GetCounterResponse {}


  }

  object Counter {

    def activeCounter(value: Int, maxValue: Int): ActiveCounter = ActiveCounter(value, maxValue)

    def uninitializedCounter(): UninitializedCounter = UninitializedCounter()

    def stoppedCounter(value: Int): StoppedCounter = StoppedCounter(value)

    case class UninitializedCounter() extends Counter

    case class ActiveCounter(value: Int, maxValue: Int) extends Counter

    case class StoppedCounter(value: Int) extends Counter

  }


}
