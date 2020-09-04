package com.example.counter.impl

import com.example.counter.api.CounterApi.CounterCommandRequest
import com.example.counter.api._
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent.CounterStarted
import com.example.counter.domain.CounterDomain.CounterState
import io.cloudstate.entity.CommandEffects

class StoppedCounterHandlerImpl extends StoppedCounterDefaultHandler {

  /*
  * Command Handler(s)
  * */

  override def handleStartRequest(stoppedCounter: CounterState.StoppedCounter,
                                  startRequest: CounterCommandRequest.StartRequest
                                 ): CommandEffects[CounterDomain.CounterEvent] =
    CommandEffects.accept(CounterStarted(startRequest.maxValue))


  /*
  * Event Handler(s)
  * */

  override def handleCounterStarted(stoppedCounter: CounterState.StoppedCounter,
                                    counterStarted: CounterStarted): CounterState =
    CounterDomain.CounterState.activeCounter(stoppedCounter.value, counterStarted.maxValue)


  /*
  * Response object mapper
  * */

  def mapDomainCounterToApiCounter(stoppedCounter: CounterDomain.CounterState.StoppedCounter): CounterApi.Counter =
    CounterApi.Counter.stoppedCounter(stoppedCounter.value)

}
