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

package io.cloudstate.tck

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import scala.collection.JavaConverters._

final case class TckProcessConfig private (
    hostname: String,
    port: Int,
    directory: File,
    command: List[String],
    stopCommand: Option[List[String]],
    envVars: Map[String, String],
    dockerImage: String,
    dockerArgs: List[String],
) {
  def validate(): Unit =
    if (dockerImage.nonEmpty) {
      require(command.isEmpty, "Either docker-image or command must be specified, but not both")
      require(stopCommand.isEmpty, "Either docker-image or stop command must be specified, but not both")
    } else {
      require(command.nonEmpty, "One of docker-image or command must be specified")
      require(directory.exists, s"Configured directory ($directory) does not exist")
      require(directory.isDirectory, s"Configured directory ($directory) is not a directory")
    }
}

object TckProcessConfig {
  def parseFrom(config: Config): TckProcessConfig =
    TckProcessConfig(
      hostname = config.getString("hostname"),
      port = config.getInt("port"),
      directory = new File(config.getString("directory")),
      command = config.getStringList("command").asScala.toList,
      stopCommand = Some(config.getStringList("stop-command").asScala.toList).filter(_.nonEmpty),
      envVars = config.getConfig("env-vars").root.unwrapped.asScala.toMap.map {
        case (key, value: AnyRef) => key -> value.toString
      },
      dockerImage = config.getString("docker-image"),
      dockerArgs = config.getStringList("docker-args").asScala.toList
    )
}

final case class TckConfiguration private (name: String,
                                           proxy: TckProcessConfig,
                                           service: TckProcessConfig,
                                           tckHostname: String,
                                           tckPort: Int) {

  def validate(): Unit = {
    proxy.validate()
    service.validate()
    // FIXME implement
  }
}

object TckConfiguration {
  def fromConfig(config: Config): TckConfiguration = {
    val reference = ConfigFactory.defaultReference().getConfig("cloudstate-tck")
    val c = config.withFallback(reference)
    TckConfiguration(
      name = c.getString("name"),
      proxy = TckProcessConfig.parseFrom(c.getConfig("proxy")),
      service = TckProcessConfig.parseFrom(c.getConfig("service")),
      tckHostname = c.getString("tck.hostname"),
      tckPort = c.getInt("tck.port")
    )
  }
}
