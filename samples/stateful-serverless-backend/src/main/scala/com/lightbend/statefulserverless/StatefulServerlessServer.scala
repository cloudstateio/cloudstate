package com.lightbend.statefulserverless

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import akka.http.scaladsl.{ Http, HttpConnectionContext, UseHttp2 }
import akka.http.scaladsl.Http.ServerBinding
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
//import com.lightbend.statefulserverless.grpc.StatefulServerlessServiceHandler

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{ Success, Failure }

object StatefulServerlessServer {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("statefulserverless-backend")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val config = system.settings.config

    scala.sys.addShutdownHook { Await.ready(system.terminate(), 30.seconds) } // TODO make timeout configurable

    val httpInterface = "127.0.0.1" // TODO Make configurable?
    val httpPort = config.getInt("http.port")

    Future.unit.flatMap({ _ =>
      val httpServerFuture = Http().bindAndHandleAsync(
        StatefulServerlessServiceHandler(new StatefulServerlessServiceImpl(system)), // TODO Implement
        interface = httpInterface,
        port = httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always))

      if (!config.getBoolean("dev")) { // TODO move away from configuring this explicitly?
        AkkaManagement(system).start()
        ClusterBootstrap(system).start()
      }

      httpServerFuture
    }).transform(Success(_)).foreach {
      case Success(ServerBinding(localAddress)) =>
        println(s"StatefulServerless backend online at $localAddress")
      case Failure(t) => 
        t.printStackTrace()
        scala.sys.exit(1)
    }
  }
}
