package io.cloudstate.tck

import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters._

class TCK extends Suites({
  val config = ConfigFactory.load()
  val combinations = config.getConfigList("cloudstate-tck.combinations")
  val validNames = combinations.iterator.asScala.map(_.getString("name")).toSet
  val verify = config.getStringList("cloudstate-tck.verify").asScala.toSet

  verify.filterNot(validNames) match {
    case x if !x.isEmpty =>
      System.err.println("Configuration 'cloudstate-tck.verify' contains non-existent names for combinations:" + x.mkString("[",",","]"))
    case _ => // All good
  }

   combinations.
    iterator.
    asScala.
    filter(section => verify(section.getString("name"))).
    map(c => new CloudStateTCK(CloudStateTCK.Configuration(c))).
    toVector
  }: _*) with SequentialNestedSuiteExecution
