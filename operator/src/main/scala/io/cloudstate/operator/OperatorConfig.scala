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
        noStore = imageConfig.getString("no-store"),
        postgres = imageConfig.getString("postgres")
      )
    )
  }
}

case class ImageConfig(cassandra: String, inMemory: String, noStore: String, postgres: String)
