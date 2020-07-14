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
