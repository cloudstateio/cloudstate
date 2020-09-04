package com.example.counter.impl

import com.example.counter.api.CounterApi.CounterCommandRequest._
import com.example.counter.api._
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent
import com.example.counter.domain.CounterDomain.CounterEvent.{CounterIncremented, CounterStopped}
import com.example.counter.domain.CounterDomain.CounterState.ActiveCounter
import io.cloudstate.entity.CommandEffects

/*
* Handlers for an ActiveCounter
* */

class ActiveCounterHandlerImpl extends ActiveCounterDefaultHandler {

  /*
  * Command Handlers
  * */

  override def handleIncrementRequest(activeCounter: ActiveCounter,
                                      incrementRequest: IncrementRequest): CommandEffects[CounterEvent] =
    CommandEffects.accept(CounterIncremented(incrementRequest.amount))

  override def handleStopRequest(activeCounter: ActiveCounter, stopRequest: StopRequest): CommandEffects[CounterEvent] =
    CommandEffects.accept(CounterStopped())

  /*
  * Event Handlers
  * */

  override def handleCounterIncremented(activeCounter: ActiveCounter,
                                        counterIncremented: CounterEvent.CounterIncremented
                                       ): CounterDomain.CounterState =
    CounterDomain.CounterState.activeCounter(activeCounter.value + counterIncremented.amount,
      activeCounter.maxValue) // Use lens here to copy current state

  override def handleCounterStopped(activeCounter: ActiveCounter,
                                    counterStopped: CounterEvent.CounterStopped): CounterDomain.CounterState =
    CounterDomain.CounterState.stoppedCounter(activeCounter.value)

  /*
  * Response object mapper
  * */

  override def mapDomainCounterToApiCounter(activeCounter: ActiveCounter): CounterApi.Counter =
    CounterApi.Counter.activeCounter(activeCounter.value, activeCounter.maxValue)
}
