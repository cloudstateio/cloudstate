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

package io.cloudstate.javasupport

import java.time.Duration

import com.typesafe.config.{Config, ConfigFactory}

/**
 * The CloudStateConfigHolder is responsible for loading and holding the default configuration.
 * This configuration is used by [[io.cloudstate.javasupport.CloudState]] and
 * [[io.cloudstate.javasupport.PassivationStrategy#defaultTimeout()]].
 */
private[javasupport] object CloudStateConfigHolder {

  private val configuration: Config = {
    val conf = ConfigFactory.load()
    conf.getConfig("cloudstate.system").withFallback(conf)
  }

  def defaultConfiguration(): Config = configuration

  def defaultPassivationTimeout(): Duration = configuration.getDuration("cloudstate.passivation-timeout")
}
