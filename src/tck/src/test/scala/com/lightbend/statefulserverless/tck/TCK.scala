package com.lightbend.statefulserverless.tck

import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}
import scala.collection.JavaConverters._

class TCK extends Suites(ConfigFactory.load().getConfigList("stateful-serverless-tck.combinations").
           iterator.
           asScala.
           map(c => new StatefulServerlessTCK(new StatefulServerlessTCK.Configuration(c))).
           toVector: _*) with SequentialNestedSuiteExecution
