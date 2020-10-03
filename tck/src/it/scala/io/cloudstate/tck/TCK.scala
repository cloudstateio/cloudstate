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

import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.testkit.ServiceAddress

import scala.jdk.CollectionConverters._

object TCK {

  private def tckSuites(combinations: List[Config], verify: Set[String]): Vector[ManagedCloudStateTckSpec] = {
    val eventSourced = tckSuite(combinations, verify, c => new ManagedCloudStateTCK(TckConfiguration.fromConfig(c)))
    val crud = tckSuite(combinations, verify, c => new ManagedCloudStateCrudTCK(TckConfiguration.fromConfig(c)))
    (eventSourced ++ crud).toVector
  }

  private def tckSuite(combinations: List[Config],
                       verify: Set[String],
                       factory: Config => ManagedCloudStateTckSpec): Iterator[ManagedCloudStateTckSpec] =
    combinations
      .iterator
      .filter(section => verify(section.getString("name"))).
      map(c => factory(c))

}

class TCK extends Suites({
  val config = ConfigFactory.load()
  val combinations = config.getConfigList("cloudstate-tck.combinations")
  val validNames = combinations.iterator.asScala.map(_.getString("name")).toSet
  val verify = config.getStringList("cloudstate-tck.verify").asScala.toSet

  verify.filterNot(validNames) match {
    case x if !x.isEmpty =>
      throw new IllegalArgumentException("Configuration 'cloudstate-tck.verify' contains non-existent names for combinations:" + x.mkString("[",",","]"))
    case _ => // All good
  }

  TCK.tckSuites(combinations.asScala.toList, verify)

  }: _*) with SequentialNestedSuiteExecution

object ManagedCloudStateTCK {
  def settings(config: TckConfiguration): CloudStateTCK.Settings = {
    CloudStateTCK.Settings(
      ServiceAddress(config.tckHostname, config.tckPort),
      ServiceAddress(config.proxy.hostname, config.proxy.port),
      ServiceAddress(config.service.hostname, config.service.port)
    )
  }
}

/* TCK test suite for EventSourcing */
class ManagedCloudStateTCK(override val config: TckConfiguration)
  extends CloudStateTCK("for " + config.name, ManagedCloudStateTCK.settings(config))
  with ManagedCloudStateTckSpec

/* TCK test suite for CRUD */
class ManagedCloudStateCrudTCK(override val config: TckConfiguration)
  extends CloudStateCrudTCK("for " + config.name, ManagedCloudStateTCK.settings(config))
  with ManagedCloudStateTckSpec

trait ManagedCloudStateTckSpec extends WordSpecLike with BeforeAndAfterAll {

  val config: TckConfiguration

  config.validate()

  private val processes: TckProcesses = TckProcesses.create(config)

  override def beforeAll(): Unit = try {
    processes.service.start()
    super.beforeAll()
    processes.proxy.start()
  } catch {
    case error: Throwable =>
      processes.service.logs("service")
      processes.proxy.logs("proxy")
      throw error
  }

  override def afterAll(): Unit = {
    try processes.proxy.stop()
    finally try processes.service.stop()
    finally super.afterAll()
  }

  // only print the process logs on failures
  override protected def withFixture(test: NoArgTest): Outcome = {
    super.withFixture(test) match {
      case failed: Failed =>
        alert("Playing buffered logs for failed test:")
        processes.proxy.logs("proxy")
        processes.service.logs("service")
        failed
      case other => other
    }
  }
}
