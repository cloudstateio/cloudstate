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
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._

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

   combinations.
    iterator.
    asScala.
    filter(section => verify(section.getString("name"))).
    map(c => new ManagedCloudStateTCK(TckConfiguration.fromConfig(c))).
    toVector
  }: _*) with SequentialNestedSuiteExecution

object ManagedCloudStateTCK {
  def settings(config: TckConfiguration): CloudStateTCK.Settings = {
    CloudStateTCK.Settings(
      CloudStateTCK.Address(config.tckHostname, config.tckPort),
      CloudStateTCK.Address(config.proxy.hostname, config.proxy.port),
      CloudStateTCK.Address(config.frontend.hostname, config.frontend.port)
    )
  }
}

class ManagedCloudStateTCK(config: TckConfiguration) extends CloudStateTCK("for " + config.name, ManagedCloudStateTCK.settings(config)) {
  config.validate()

  val processes: TckProcesses =  TckProcesses.create(config)

  override def beforeAll(): Unit = {
    processes.frontend.start()
    super.beforeAll()
    processes.proxy.start()
  }

  override def afterAll(): Unit = {
    try Option(processes).foreach(_.proxy.stop())
    finally try Option(processes).foreach(_.frontend.stop())
    finally super.afterAll()
  }
}
