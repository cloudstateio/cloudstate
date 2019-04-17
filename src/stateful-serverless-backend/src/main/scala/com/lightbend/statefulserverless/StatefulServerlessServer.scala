package com.lightbend.statefulserverless

import akka.actor.{ActorSystem, Props}
import akka.cluster.Cluster
import akka.util.Timeout
import akka.stream.ActorMaterializer
import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding
import akka.cluster.sharding._
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.grpc.GrpcClientSettings
import com.typesafe.config.Config
import com.lightbend.statefulserverless.grpc._
import com.google.protobuf.empty.Empty

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object StatefulServerlessServer {
  private final case class Configuration private (
    devMode: Boolean,
    httpInterface: String,
    httpPort: Int,
    userFunctionInterface: String,
    userFunctionPort: Int,
    userFunctionName: String,
    relayTimeout: Timeout,
    relayOutputBufferSize: Int,
    passivationTimeout: Timeout,
    numberOfShards: Int) {
      def this(config: Config) =
        this(
          devMode               = config.getBoolean("dev-mode-enabled"),
          httpInterface         = config.getString("http-interface"),
          httpPort              = config.getInt("http-port"),
          userFunctionInterface = config.getString("user-function-interface"),
          userFunctionPort      = config.getInt("user-function-port"),
          userFunctionName      = config.getString("user-function-name"),
          relayTimeout          = Timeout(config.getDuration("relay-timeout").toMillis.millis),
          relayOutputBufferSize = config.getInt("relay-buffer-size"),
          passivationTimeout    = Timeout(config.getDuration("passivation-timeout").toMillis.millis),
          numberOfShards        = config.getInt("number-of-shards")
        )
    }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("statefulserverless-backend")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    // FIXME go over and supply appropriate values for Cluster Sharding
    // https://doc.akka.io/docs/akka/current/cluster-sharding.html?language=scala#configuration
    val config = new Configuration(system.settings.config.getConfig("stateful-serverless"))

    scala.sys.addShutdownHook { Await.ready(system.terminate(), 30.seconds) } // TODO make timeout configurable

    val clientSettings     = GrpcClientSettings.connectToServiceAt(config.userFunctionInterface, config.userFunctionPort).withTls(false)
    val client             = EntityClient(clientSettings) // FIXME configure some sort of retries?

    val stateManagerConfig = StateManager.Configuration(config.userFunctionName, config.passivationTimeout, config.relayOutputBufferSize)


    // Bootstrap the cluster
    if (config.devMode) {
      // In development, we just have a cluster of one, so we join ourself.
      val cluster = Cluster(system)
      cluster.join(cluster.selfAddress)
    } else {
      AkkaManagement(system).start()
      ClusterBootstrap(system).start()
    }

    Future.unit.map({ _ =>
      ClusterSharding(system).start(
        typeName = config.userFunctionName, // FIXME derive name from the actual proxied service?
        entityProps = Props(new StateManager(client, stateManagerConfig)), // FIXME investigate dispatcher config
        settings = ClusterShardingSettings(system),
        messageExtractor = new Serve.CommandMessageExtractor(config.numberOfShards))
    }).flatMap({ stateManager =>
      // FIXME introduce some kind of retry policy here
      client.ready(Empty.of()).map(reply => Serve.createRoute(stateManager, config.relayTimeout, reply))
    }).flatMap({ route =>
      Http().bindAndHandleAsync(
        route,
        interface = config.httpInterface,
        port = config.httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always))
    }).transform(Success(_)).foreach {
      case Success(ServerBinding(localAddress)) =>
        println(config.httpPort)
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
