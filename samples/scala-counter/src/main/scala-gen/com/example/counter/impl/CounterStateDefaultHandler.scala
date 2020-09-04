/*
* Pseudo-Generated
* */

package com.example.counter.impl

import com.example.counter.api.CounterApi
import com.example.counter.api.CounterApi.CounterCommandRequest._
import com.example.counter.api.CounterApi.CounterCommandResponse._
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent
import com.example.counter.domain.CounterDomain.CounterEvent.CounterInitialized

trait CounterStateDefaultHandler {
  /*
  * Command handlers
  * */
  def handleInitializeRequest(counterState: CounterDomain.CounterState, initializeRequest: InitializeRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStartRequest(counterState: CounterDomain.CounterState, startRequest: StartRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleIncrementRequest(counterState: CounterDomain.CounterState, incrementRequest: IncrementRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStopRequest(counterState: CounterDomain.CounterState, stopRequest: StopRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleGetCounterRequest(counterState: CounterDomain.CounterState, getCounterRequest: GetCounterRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  /*
  * Event handlers
  * */
  def handleCounterInitialized(counterState: CounterDomain.CounterState, counterInitialized: CounterInitialized): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStarted(counterState: CounterDomain.CounterState, counterStarted: CounterEvent.CounterStarted): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterIncremented(counterState: CounterDomain.CounterState, counterIncremented: CounterEvent.CounterIncremented): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStopped(counterState: CounterDomain.CounterState, counterStopped: CounterEvent.CounterStopped): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  /*
  * Response builders
  * */

  def buildInitializeResponse(counterState: CounterDomain.CounterState, initializeRequest: InitializeRequest): InitializeResponse = throw new UnsupportedOperationException("No response")

  def buildStartResponse(counterState: CounterDomain.CounterState, startRequest: StartRequest): StartResponse = throw new UnsupportedOperationException("No response")

  def buildIncrementResponse(counterState: CounterDomain.CounterState, incrementRequest: IncrementRequest): IncrementResponse = throw new UnsupportedOperationException("No response")

  def buildStopResponse(counterState: CounterDomain.CounterState, stopRequest: StopRequest): StopResponse = throw new UnsupportedOperationException("No response")

  def buildGetCounterResponse(counterState: CounterDomain.CounterState, getCounterRequest: GetCounterRequest): GetCounterResponse = throw new UnsupportedOperationException("No response")

  /*
  * domain-to-api object map
  * */

  def mapDomainCounterToApiCounter(activeCounter: CounterDomain.CounterState.UninitializedCounter): CounterApi.Counter

  def mapDomainCounterToApiCounter(activeCounter: CounterDomain.CounterState.ActiveCounter): CounterApi.Counter

  def mapDomainCounterToApiCounter(activeCounter: CounterDomain.CounterState.StoppedCounter): CounterApi.Counter
}
