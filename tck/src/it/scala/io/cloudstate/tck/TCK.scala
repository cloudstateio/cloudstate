package io.cloudstate.tck

import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters._

class TCK extends Suites(ConfigFactory.load().getConfigList("cloudstate-tck.combinations").
           iterator.
           asScala.
           map(c => new CloudStateTCK(CloudStateTCK.Configuration(c))).
           toVector: _*) with SequentialNestedSuiteExecution
