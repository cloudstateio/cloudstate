package com.lightbend.statefulserverless

import akka.actor.{ ActorSystem, Props }
import akka.util.Timeout
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

    // FIXME go over and supply appropriate values for Cluster Sharding
    // https://doc.akka.io/docs/akka/current/cluster-sharding.html?language=scala#configuration
    val config = system.settings.config

    scala.sys.addShutdownHook { Await.ready(system.terminate(), 30.seconds) } // TODO make timeout configurable

    val httpInterface    = "127.0.0.1" // FIXME Make configurable
    val httpPort         = config.getInt("http.port")

    val clientSettings   = GrpcClientSettings.fromConfig(Entity.name)
    val client           = EntityClient(clientSettings) // FIXME configure some sort of retries?

    implicit val timeout = Timeout(5.seconds) // FIXME load from `config`
    val shards           = 100 // FIZME load from `config`

    Future.unit.map({ _ =>
      AkkaManagement(system).start()

      ClusterBootstrap(system).start()

      ClusterSharding(system).start(
        typeName = "StateManager",
        entityProps = Props(classOf[StateManager], client), // FIXME investigate dispatcher config
        settings = ClusterShardingSettings(system),
        messageExtractor = new Serve.CommandMessageExtractor(shards))
    }).flatMap({ stateManager =>
      client.ready(Empty.of()).map(Serve.createRoute(stateManager)) // FIXME introduce some kind of retry policy here
    }).flatMap({ route =>
      Http().bindAndHandleAsync(
        route,
        interface = httpInterface,
        port = httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always))
    }).transform(Success(_)).foreach {
      case Success(ServerBinding(localAddress)) =>
        println(s"StatefulServerless backend online at $localAddress")
      case Failure(t) => 
        t.printStackTrace()
        // FIXME figure out what the cleanest exist looks like
        materializer.shutdown()
        system.terminate().andThen({
          case _ => scala.sys.exit(1)
        })
    }
  }
}
