package com.lightbend.statefulserverless

import akka.Done
import akka.actor.{ActorRef, Actor, ActorLogging, CoordinatedShutdown, Props, Status, Terminated }
import akka.util.Timeout
import akka.pattern.pipe
import akka.stream.Materializer
import akka.dispatch.ExecutionContexts
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
    httpInterface: String,
    httpPort: Int,
    userFunctionInterface: String,
    userFunctionPort: Int,
    relayTimeout: Timeout,
    relayOutputBufferSize: Int,
    passivationTimeout: Timeout,
    numberOfShards: Int,
    proxyParallelism: Int) {
    validate()
    def this(config: Config) = {
      this(
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
    }

    private[this] final def validate(): Unit = {
      require(proxyParallelism > 0)
      // TODO add more config validation here
    }
  }

  def props(config: Configuration)(implicit mat: Materializer): Props = Props(new ServerManager(config))
}

class ServerManager(config: ServerManager.Configuration)(implicit mat: Materializer) extends Actor with ActorLogging {
  import context.system
  import context.dispatcher

  private[this] final val clientSettings = GrpcClientSettings.connectToServiceAt(config.userFunctionInterface, config.userFunctionPort).withTls(false)
  private[this] final val client         = EntityClient(clientSettings)

  client.ready(Empty.of()) pipeTo self

  override def receive: Receive = {
    case reply: EntitySpec =>
      val stateManagerConfig = StateManager.Configuration(reply.persistenceId, config.passivationTimeout, config.relayOutputBufferSize)

      val stateManager = context.watch(ClusterSharding(system).start(
        typeName = reply.persistenceId,
        entityProps = StateManagerSupervisor.props(client, stateManagerConfig),
        settings = ClusterShardingSettings(system),
        messageExtractor = new Serve.CommandMessageExtractor(config.numberOfShards)))

      Http().bindAndHandleAsync(
        handler = Serve.createRoute(stateManager, config.proxyParallelism, config.relayTimeout, reply),
        interface = config.httpInterface,
        port = config.httpPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always)
      ) pipeTo self

      context.become(binding(stateManager))

    case Status.Failure(cause) =>
      // Failure to load the entity spec is not fatal, simply crash and let the backoff supervisor restart us
      throw cause
  }

  private[this] final def binding(stateManager: ActorRef): Receive = {
    case binding: ServerBinding =>
      log.info(s"StatefulServerless backend online at ${binding.localAddress}")

      // These can be removed if https://github.com/akka/akka-http/issues/1210 ever gets implemented
      val shutdown = CoordinatedShutdown(system)

      shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
        binding.unbind().map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
        binding.terminate(10.seconds).map(_ => Done) // TODO make configurable?
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "http-shutdown") { () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
      }

      context.become(running(stateManager))

    case Terminated(`stateManager`) =>
      log.error("StateManager terminated during initialization of server")
      system.terminate()

    case Status.Failure(cause) =>
      // Failure to bind the HTTP server is fatal, terminate
      log.error(cause, "Failed to bind HTTP server")
      system.terminate()
  }

  /** Nothing to do when running */
  private[this] final def running(stateManager: ActorRef): Receive = {
    case Terminated(`stateManager`) => // TODO How to handle the termination of the stateManager during runtime?
  }

  override final def postStop(): Unit = {
    super.postStop()
    client.close()
    // TODO do we system.terminate() here?
  }
}
