package com.lightbend.statefulserverless.operator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import skuber._

object OperatorMain extends App {

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  import system.dispatcher
  val client = k8sInit

  new EventSourcedServiceOperator(client).run()

}
