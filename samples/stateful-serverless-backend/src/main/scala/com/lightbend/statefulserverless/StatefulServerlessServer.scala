package com.lightbend.statefulserverless

import akka.actor.{ ActorSystem, Props }
import akka.stream.ActorMaterializer

import akka.http.scaladsl.{ Http, HttpConnectionContext, UseHttp2 }
import akka.http.scaladsl.Http.ServerBinding

import akka.cluster.sharding._

import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.grpc.GrpcClientSettings

import com.lightbend.statefulserverless.grpc._
import com.google.protobuf.empty.Empty

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

    val clientSettings = GrpcClientSettings.fromConfig(Entity.name)
    val client = EntityClient(clientSettings)

    Future.unit.map({ _ =>
      AkkaManagement(system).start()

      ClusterBootstrap(system).start()

      ClusterSharding(system).start(
        typeName = "StateManager",
        entityProps = Props[StateManager], // FIXME pass in client or settings
        settings = ClusterShardingSettings(system),
        extractEntityId = ???,
        extractShardId = ???)
    }).flatMap({ stateManager =>
      // FIXME introduce some kind of retry policy here
      client.ready(Empty.of()).map(Serve.createRoute(stateManager)) // FIXME pass in the stateManager
    }).flatMap({ route =>
      val httpServerFuture = Http().bindAndHandleAsync(
        route,
        interface = httpInterface,
        port = httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always))

      httpServerFuture
    }).transform(Success(_)).foreach {
      case Success(ServerBinding(localAddress)) =>
        println(s"StatefulServerless backend online at $localAddress")
      case Failure(t) => 
        t.printStackTrace()
        scala.sys.exit(1) // FIXME figure out what the cleanest exist looks like
    }
  }
}
