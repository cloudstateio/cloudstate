/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.impl

import io.cloudstate.StatefulService
import io.cloudstate.function._

import akka.actor.ActorSystem

class StatelessFunctionImpl(system: ActorSystem, service: StatefulService) extends StatelessFunction {
  
  
  override def handleUnary(in: io.cloudstate.function.FunctionCommand): scala.concurrent.Future[io.cloudstate.function.FunctionReply] = ???
  
  
  def handleStreamedIn(in: akka.stream.scaladsl.Source[io.cloudstate.function.FunctionCommand, akka.NotUsed]): scala.concurrent.Future[io.cloudstate.function.FunctionReply] = ???
  
  
  def handleStreamedOut(in: io.cloudstate.function.FunctionCommand): akka.stream.scaladsl.Source[io.cloudstate.function.FunctionReply, akka.NotUsed] = ???
  
  
  def handleStreamed(in: akka.stream.scaladsl.Source[io.cloudstate.function.FunctionCommand, akka.NotUsed]): akka.stream.scaladsl.Source[io.cloudstate.function.FunctionReply, akka.NotUsed] = ???
  
}