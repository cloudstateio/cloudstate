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
import com.example.counter.domain.CounterDomain.CounterState.UninitializedCounter
import com.example.counter.domain.event.{CounterIncremented, CounterStarted, CounterStopped}

trait UninitializedCounterDefaultHandler {
  /*
  * Command handlers
  * */
  def handleInitializeRequest(uninitializedCounter: UninitializedCounter, initializeRequest: InitializeRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStartRequest(uninitializedCounter: UninitializedCounter, startRequest: StartRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleIncrementRequest(uninitializedCounter: UninitializedCounter, incrementRequest: IncrementRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleStopRequest(uninitializedCounter: UninitializedCounter, stopRequest: StopRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  def handleGetCounterRequest(uninitializedCounter: UninitializedCounter, getCounterRequest: GetCounterRequest): CommandEffects[CounterEvent] = CommandEffects.reject("Not implemented")

  /*
  * Event handlers
  * */
  def handleCounterInitialized(uninitializedCounter: UninitializedCounter, counterInitialized: CounterInitialized): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStarted(uninitializedCounter: UninitializedCounter, counterStarted: CounterEvent.CounterStarted): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterIncremented(uninitializedCounter: UninitializedCounter, counterIncremented: CounterEvent.CounterIncremented): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  def handleCounterStopped(uninitializedCounter: UninitializedCounter, counterStopped: CounterEvent.CounterStopped): CounterDomain.CounterState = CounterDomain.CounterState.unhandled

  /*
  * Response builders
  * */

  def buildInitializeResponse(uninitializedCounter: UninitializedCounter, initializeRequest: InitializeRequest): InitializeResponse = throw new UnsupportedOperationException("No response")

  def buildStartResponse(uninitializedCounter: UninitializedCounter, startRequest: StartRequest): StartResponse = throw new UnsupportedOperationException("No response")

  def buildIncrementResponse(uninitializedCounter: UninitializedCounter, incrementRequest: IncrementRequest): IncrementResponse = throw new UnsupportedOperationException("No response")

  def buildStopResponse(uninitializedCounter: UninitializedCounter, stopRequest: StopRequest): StopResponse = throw new UnsupportedOperationException("No response")

  def buildGetCounterResponse(uninitializedCounter: UninitializedCounter, getCounterRequest: GetCounterRequest): GetCounterResponse = throw new UnsupportedOperationException("No response")

  /*
  * domain-to-api object map
  * */

  def mapDomainCounterToApiCounter(uninitializedCounter: CounterDomain.CounterState.UninitializedCounter): CounterApi.Counter

}
