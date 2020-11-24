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

package io.cloudstate.javasupport.impl

import java.time.Duration

import com.typesafe.config.ConfigFactory
import io.cloudstate.javasupport.PassivationStrategy

private[impl] case class Timeout(duration: Duration) extends PassivationStrategy

private[impl] object Timeout {

  private val defaultPassivationTimeout: Duration = {
    val config = ConfigFactory.load()
    config.getDuration("cloudstate.passivation-timeout")
  }

  def apply(): Timeout = Timeout(defaultPassivationTimeout)
}
