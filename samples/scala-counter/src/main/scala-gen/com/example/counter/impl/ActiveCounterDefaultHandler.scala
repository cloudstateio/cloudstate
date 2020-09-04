/*
* Pseudo-Generated
* */

package com.example.counter.impl

import com.example.counter.api.CounterApi.CounterCommandRequest.{GetCounterRequest, IncrementRequest, InitializeRequest, StartRequest, StopRequest}
import com.example.counter.api.CounterApi.CounterCommandResponse.{GetCounterResponse, IncrementResponse, InitializeResponse, StartResponse, StopResponse}
import com.example.counter.api._
import com.example.counter.domain.CounterDomain
import com.example.counter.domain.CounterDomain.CounterEvent
import com.example.counter.domain.CounterDomain.CounterEvent.CounterInitialized
import com.example.counter.domain.CounterDomain.CounterState.ActiveCounter
import com.example.counter.domain.event.{CounterIncremented, CounterStarted, CounterStopped}

trait ActiveCounterDefaultHandler {
  /*
  * Command handlers
  * */
  def handleInitializeRequest(activeCounter: ActiveCounter, initializeRequest: InitializeRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStartRequest(activeCounter: ActiveCounter, startRequest: StartRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleIncrementRequest(activeCounter: ActiveCounter, incrementRequest: IncrementRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStopRequest(activeCounter: ActiveCounter, stopRequest: StopRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleGetCounterRequest(activeCounter: ActiveCounter, getCounterRequest: GetCounterRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  /*
  * Event handlers
  * */
  def handleCounterInitialized(activeCounter: ActiveCounter, counterInitialized: CounterInitialized): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStarted(activeCounter: ActiveCounter, counterStarted: CounterEvent.CounterStarted): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterIncremented(activeCounter: ActiveCounter, counterIncremented: CounterEvent.CounterIncremented): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStopped(activeCounter: ActiveCounter, counterStopped: CounterEvent.CounterStopped): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  /*
  * Response builders
  * */

  def buildInitializeResponse(activeCounter: ActiveCounter, initializeRequest: InitializeRequest): InitializeResponse = throw new UnsupportedOperationException("No response")

  def buildStartResponse(activeCounter: ActiveCounter, startRequest: StartRequest): StartResponse = throw new UnsupportedOperationException("No response")

  def buildIncrementResponse(activeCounter: ActiveCounter, incrementRequest: IncrementRequest): IncrementResponse = throw new UnsupportedOperationException("No response")

  def buildStopResponse(activeCounter: ActiveCounter, stopRequest: StopRequest): StopResponse = throw new UnsupportedOperationException("No response")

  def buildGetCounterResponse(activeCounter: ActiveCounter, getCounterRequest: GetCounterRequest): GetCounterResponse = throw new UnsupportedOperationException("No response")

  /*
  * domain-to-api object map
  * */

  def mapDomainCounterToApiCounter(activeCounter: ActiveCounter): CounterApi.Counter

}
