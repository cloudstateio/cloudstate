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
