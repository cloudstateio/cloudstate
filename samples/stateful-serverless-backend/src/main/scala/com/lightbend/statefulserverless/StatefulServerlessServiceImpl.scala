package com.lightbend.statefulserverless

import akka.actor.ActorSystem
import scala.concurrent.Future

// FIXME remove this and replace it with the proto generated one
case class StatefulServerlessServiceHandler(impl: StatefulServerlessServiceImpl) extends (akka.http.scaladsl.model.HttpRequest => scala.concurrent.Future[akka.http.scaladsl.model.HttpResponse]) {
  override def apply(in: akka.http.scaladsl.model.HttpRequest): scala.concurrent.Future[akka.http.scaladsl.model.HttpResponse] =
    Future.failed(new IllegalStateException("not implemented"))
}

// TODO implement
class StatefulServerlessServiceImpl(system: ActorSystem) /* extends StatefulServerlessService */ {

}