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
    dockerImage: String
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
      dockerImage = config.getString("docker-image")
    )
}

final case class TckConfiguration private (name: String,
                                           proxy: TckProcessConfig,
                                           frontend: TckProcessConfig,
                                           tckHostname: String,
                                           tckPort: Int) {

  def validate(): Unit = {
    proxy.validate()
    frontend.validate()
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
      frontend = TckProcessConfig.parseFrom(c.getConfig("frontend")),
      tckHostname = c.getString("tck.hostname"),
      tckPort = c.getInt("tck.port")
    )
  }
}
