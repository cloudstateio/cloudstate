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

package io.cloudstate.proxy.telemetry

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import io.prometheus.client.CollectorRegistry
import org.scalatest.concurrent.Eventually
import org.scalatest.{Matchers, WordSpecLike}
import scala.io.Source

abstract class AbstractTelemetrySpec extends WordSpecLike with Matchers with Eventually {

  def withTestRegistry(conf: String = "")(run: TestKit => Any): Unit = {
    // avoid multiple registrations on the global default registry in tests by creating a new registry
    val testConf = conf + """
      | cloudstate.proxy.telemetry.prometheus.use-default-registry = false
      """
    withTestKit(testConf)(run)
  }

  def withTestKit(conf: String)(run: TestKit => Any): Unit = {
    val config = ConfigFactory.load(ConfigFactory.parseString(conf.stripMargin))
    val testKit = new TestKit(ActorSystem(getClass.getSimpleName, config))
    try run(testKit)
    finally testKit.shutdown()
  }

  def scrape[T](url: String)(useSource: Source => T): T = {
    val source = Source.fromURL(url)
    try useSource(source)
    finally source.close()
  }

  def metricValue(name: String, labels: (String, String)*)(implicit collectorRegistry: CollectorRegistry): Double =
    collectorRegistry.getSampleValue(name, labels.map(_._1).toArray, labels.map(_._2).toArray)

}
