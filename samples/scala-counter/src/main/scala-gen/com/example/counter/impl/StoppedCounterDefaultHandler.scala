/*
* Pseudo-Generated
* */

package com.example.counter.impl

import com.example.counter.api.CounterApi.CounterCommandRequest._
import com.example.counter.api.CounterApi.CounterCommandResponse.{GetCounterResponse, IncrementResponse, InitializeResponse, StartResponse, StopResponse}
import com.example.counter.api._
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent
import com.example.counter.domain.CounterDomain.CounterEvent.CounterInitialized
import com.example.counter.domain.CounterDomain.CounterState.StoppedCounter

trait StoppedCounterDefaultHandler {
  /*
  * Command handlers
  * */
  def handleInitializeRequest(stoppedCounter: StoppedCounter, initializeRequest: InitializeRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStartRequest(stoppedCounter: StoppedCounter, startRequest: StartRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleIncrementRequest(stoppedCounter: StoppedCounter, incrementRequest: IncrementRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStopRequest(stoppedCounter: StoppedCounter, stopRequest: StopRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleGetCounterRequest(stoppedCounter: StoppedCounter, getCounterRequest: GetCounterRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  /*
  * Event handlers
  * */

  def handleCounterInitialized(stoppedCounter: StoppedCounter, counterInitialized: CounterInitialized): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStarted(stoppedCounter: StoppedCounter, counterStarted: CounterEvent.CounterStarted): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterIncremented(stoppedCounter: StoppedCounter, counterIncremented: CounterEvent.CounterIncremented): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStopped(stoppedCounter: StoppedCounter, counterStopped: CounterEvent.CounterStopped): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  /*
* Response builders
* */

  def buildInitializeResponse(stoppedCounter: StoppedCounter, initializeRequest: InitializeRequest): InitializeResponse = throw new UnsupportedOperationException("No response")

  def buildStartResponse(stoppedCounter: StoppedCounter, startRequest: StartRequest): StartResponse = throw new UnsupportedOperationException("No response")

  def buildIncrementResponse(stoppedCounter: StoppedCounter, incrementRequest: IncrementRequest): IncrementResponse = throw new UnsupportedOperationException("No response")

  def buildStopResponse(stoppedCounter: StoppedCounter, stopRequest: StopRequest): StopResponse = throw new UnsupportedOperationException("No response")

  def buildGetCounterResponse(stoppedCounter: StoppedCounter, getCounterRequest: GetCounterRequest): GetCounterResponse = throw new UnsupportedOperationException("No response")

  /*
  * domain-to-api object map
  * */

  def mapDomainCounterToApiCounter(stoppedCounter: CounterDomain.CounterState.StoppedCounter): CounterApi.Counter
}
