package com.lightbend.statefulserverless

import akka.Done
import akka.actor.{Actor, ActorLogging, CoordinatedShutdown, Props, Status}
import akka.util.Timeout
import akka.pattern.pipe
import akka.stream.Materializer
import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding
import akka.cluster.sharding._
import akka.grpc.GrpcClientSettings
import com.typesafe.config.Config
import com.lightbend.statefulserverless.grpc._
import com.google.protobuf.empty.Empty

import scala.concurrent.duration._

object ServerManager {
  final case class Configuration (
    devMode: Boolean,
    httpInterface: String,
    httpPort: Int,
    userFunctionInterface: String,
    userFunctionPort: Int,
    relayTimeout: Timeout,
    relayOutputBufferSize: Int,
    passivationTimeout: Timeout,
    numberOfShards: Int,
    proxyParallelism: Int) {
    def this(config: Config) = {
      this(
        devMode               = config.getBoolean("dev-mode-enabled"),
        httpInterface         = config.getString("http-interface"),
        httpPort              = config.getInt("http-port"),
        userFunctionInterface = config.getString("user-function-interface"),
        userFunctionPort      = config.getInt("user-function-port"),
        relayTimeout          = Timeout(config.getDuration("relay-timeout").toMillis.millis),
        relayOutputBufferSize = config.getInt("relay-buffer-size"),
        passivationTimeout    = Timeout(config.getDuration("passivation-timeout").toMillis.millis),
        numberOfShards        = config.getInt("number-of-shards"),
        proxyParallelism      = config.getInt("proxy-parallelism")
      )
      validate()
    }

    private[this] def validate(): Unit = {
      require(proxyParallelism > 0)
      // TODO add more config validation here
    }
  }

  def props(config: Configuration)(implicit mat: Materializer): Props = Props(new ServerManager(config))
}

class ServerManager(config: ServerManager.Configuration)(implicit mat: Materializer) extends Actor with ActorLogging {

  import context.dispatcher
  implicit private val system = context.system

  private val clientSettings     = GrpcClientSettings.connectToServiceAt(config.userFunctionInterface, config.userFunctionPort).withTls(false)
  private val client             = EntityClient(clientSettings)

  client.ready(Empty.of()) pipeTo self

  override def receive: Receive = {
    case reply: EntitySpec =>
      val stateManagerConfig = StateManager.Configuration(reply.persistenceId, config.passivationTimeout, config.relayOutputBufferSize)

      val stateManager = ClusterSharding(system).start(
        typeName = reply.persistenceId,
        entityProps = StateManagerSupervisor.props(client, stateManagerConfig),
        settings = ClusterShardingSettings(system),
        messageExtractor = new Serve.CommandMessageExtractor(config.numberOfShards))

     val route = Serve.createRoute(stateManager, config.proxyParallelism, config.relayTimeout, reply)

      Http().bindAndHandleAsync(
        route,
        interface = config.httpInterface,
        port = config.httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always)
      ) pipeTo self

      context.become(binding)

    case Status.Failure(cause) =>
      // Failure to load the entity spec is not fatal, simply crash and let the backoff supervisor restart us
      throw cause
  }

  private def binding: Receive = {
    case binding: ServerBinding =>
      println(s"StatefulServerless backend online at ${binding.localAddress}")

      // These can be removed if https://github.com/akka/akka-http/issues/1210 ever gets implemented
      val shutdown = CoordinatedShutdown(system)

      shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
        binding.unbind().map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
        binding.terminate(10.seconds).map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "http-shutdown") { () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
      }

      context.become(running)

    case Status.Failure(cause) =>
      // Failure to bind the HTTP server is fatal, terminate
      log.error(cause, "Failed to bind HTTP server")
      system.terminate()
  }

  /** Nothing to do when running */
  private def running: Receive = PartialFunction.empty

  override def postStop(): Unit = {
    client.close()
  }
}
