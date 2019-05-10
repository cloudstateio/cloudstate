package com.lightbend.statefulserverless.tck

import org.scalatest._
import com.typesafe.config.{Config, ConfigFactory}

class TCK extends WordSpec with MustMatchers {
  val config = ConfigFactory.load()
  "Implementations of Stateful Serverless" must {
    "pass their TCK" in {
      val combinations = config.getConfigList("stateful-serverless-tck.combinations")
       val i = combinations.iterator
       while(i.hasNext) {
          val tck = new StatefulServerlessTCK(new StatefulServerlessTCK.Configuration(i.next()))
          tck.execute()
       }
    }
  }
}
