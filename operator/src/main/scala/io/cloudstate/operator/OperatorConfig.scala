package io.cloudstate.operator

import com.typesafe.config.Config
import scala.collection.JavaConverters._

case class OperatorConfig(namespaces: List[String], images: ImageConfig)

object OperatorConfig {
  def apply(config: Config): OperatorConfig = {
    val opConfig = config.getConfig("cloudstate.operator")
    val imageConfig = opConfig.getConfig("proxy.image")
    OperatorConfig(
      namespaces = opConfig.getStringList("watch.namespaces").asScala.toList,
      images = ImageConfig(
        cassandra = imageConfig.getString("cassandra"),
        inMemory = imageConfig.getString("in-memory"),
        noJournal = imageConfig.getString("no-journal")
      )
    )
  }
}

case class ImageConfig(cassandra: String, inMemory: String, noJournal: String)