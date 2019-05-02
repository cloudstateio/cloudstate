package com.lightbend.statefulserverless.operator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object OperatorMain extends App {

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  val namespaces = sys.env.get("NAMESPACES").fold(List("default"))(_.split(",").toList)

  val runner = new OperatorRunner()
  runner.start(namespaces, new EventSourcedServiceOperatorFactory())
  runner.start(namespaces, new EventSourcedJournalOperatorFactory())
}
