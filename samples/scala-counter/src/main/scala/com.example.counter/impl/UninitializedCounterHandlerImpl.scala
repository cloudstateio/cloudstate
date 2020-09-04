package com.example.counter.impl

import com.example.counter.api.CounterApi
import com.example.counter.api.CounterApi.CounterCommandRequest
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent.CounterInitialized
import com.example.counter.domain.CounterDomain.CounterState
import io.cloudstate.entity.CommandEffects

class UninitializedCounterHandlerImpl extends UninitializedCounterDefaultHandler {

  /*
  * Command Handler(s)
  * */

  override def handleInitializeRequest(uninitializedCounter: CounterState.UninitializedCounter,
                                       initializeRequest: CounterCommandRequest.InitializeRequest
                                      ): CommandEffects[CounterDomain.CounterEvent] =
    CommandEffects.accept(CounterInitialized(initializeRequest.initialValue))

  /*
  * Event Handler(s)
  * */

  override def handleCounterInitialized(uninitializedCounter: CounterState.UninitializedCounter,
                                        counterInitialized: CounterInitialized): CounterState =
    CounterState.stoppedCounter(counterInitialized.initialValue)

  /*
  * Response object mapper
  * */

  override def mapDomainCounterToApiCounter(activeCounter: CounterState.UninitializedCounter): CounterApi.Counter =
    CounterApi.Counter.uninitializedCounter()
}
