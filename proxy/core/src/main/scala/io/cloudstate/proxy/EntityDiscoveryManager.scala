/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy

import akka.Done
import akka.actor.{Actor, ActorLogging, CoordinatedShutdown, PoisonPill, Props, Status}
import akka.cluster.Cluster
import akka.util.Timeout
import akka.pattern.pipe
import akka.stream.scaladsl.RunnableGraph
import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import akka.grpc.GrpcClientSettings
import akka.stream.ActorMaterializer
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.typesafe.config.Config
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.protocol.function.StatelessFunction
import io.cloudstate.proxy.StatsCollector.StatsCollectorSettings
import io.cloudstate.proxy.autoscaler.Autoscaler.ScalerFactory
import io.cloudstate.proxy.ConcurrencyEnforcer.ConcurrencyEnforcerSettings
import io.cloudstate.proxy.autoscaler.{
  Autoscaler,
  AutoscalerSettings,
  ClusterMembershipFacadeImpl,
  KubernetesDeploymentScaler,
  NoAutoscaler,
  NoScaler
}
import io.cloudstate.proxy.crdt.CrdtSupportFactory
import io.cloudstate.proxy.eventsourced.EventSourcedSupportFactory
import io.cloudstate.proxy.eventing.EventingManager
import io.cloudstate.proxy.function.StatelessFunctionSupportFactory

import scala.concurrent.Future
import scala.concurrent.duration._

//import io.cloudstate.protocol.entity.{ClientAction, EntityDiscovery, Failure, Reply, UserFunctionError}
import io.cloudstate.protocol.entity.EntityDiscovery
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity

object EntityDiscoveryManager {
  final case class Configuration(
      devMode: Boolean,
      httpInterface: String,
      httpPort: Int,
      userFunctionInterface: String,
      userFunctionPort: Int,
      relayTimeout: Timeout,
      relayOutputBufferSize: Int,
      maxInboundMessageSize: Long,
      gracefulTerminationTimeout: Timeout,
      passivationTimeout: Timeout,
      numberOfShards: Int,
      proxyParallelism: Int,
      concurrencySettings: ConcurrencyEnforcerSettings,
      statsCollectorSettings: StatsCollectorSettings,
      journalEnabled: Boolean,
      config: Config
  ) {
    validate()
    def this(config: Config) = {
      this(devMode = config.getBoolean("dev-mode-enabled"),
           httpInterface = config.getString("http-interface"),
           httpPort = config.getInt("http-port"),
           userFunctionInterface = config.getString("user-function-interface"),
           userFunctionPort = config.getInt("user-function-port"),
           relayTimeout = Timeout(config.getDuration("relay-timeout").toMillis.millis),
           maxInboundMessageSize = config.getBytes("max-inbound-message-size"),
           relayOutputBufferSize = config.getInt("relay-buffer-size"),
           gracefulTerminationTimeout = Timeout(config.getDuration("graceful-termination-timeout").toMillis.millis),
           passivationTimeout = Timeout(config.getDuration("passivation-timeout").toMillis.millis),
           numberOfShards = config.getInt("number-of-shards"),
           proxyParallelism = config.getInt("proxy-parallelism"),
           concurrencySettings = ConcurrencyEnforcerSettings(
             concurrency = config.getInt("container-concurrency"),
             actionTimeout = config.getDuration("action-timeout").toMillis.millis,
             cleanupPeriod = config.getDuration("action-timeout-poll-period").toMillis.millis
           ),
           statsCollectorSettings = new StatsCollectorSettings(config.getConfig("stats")),
           journalEnabled = config.getBoolean("journal-enabled"),
           config = config)
    }

    final def getConfig(path: String): Config =
      config.getConfig(path)

    private def validate(): Unit = {
      require(proxyParallelism > 0, s"proxy-parallelism must be greater than 0 but was $proxyParallelism")
      require(numberOfShards > 0, s"number-of-shards must be greater than 0 but was $numberOfShards")
      require(relayOutputBufferSize > 0, "relay-buffer-size must be greater than 0 but was $relayOutputBufferSize")
      require(maxInboundMessageSize > 0,
              "max-inbound-message-size must be greater than 0 but was $maxInboundMessageSize")
      require(maxInboundMessageSize <= Int.MaxValue,
              s"max-inbound-message-size exceeds the maximum allowed value of: ${Int.MaxValue}")
    }
  }

  def props(config: Configuration)(implicit mat: ActorMaterializer): Props =
    Props(new EntityDiscoveryManager(config))

  final case object Ready // Responds with true / false

  final def proxyInfo(supportedEntityTypes: Seq[String]) = ProxyInfo(
    protocolMajorVersion = 0,
    protocolMinorVersion = 1,
    proxyName = BuildInfo.name,
    proxyVersion = BuildInfo.version,
    supportedEntityTypes = supportedEntityTypes
  )

  final case class ServableEntity(serviceName: String,
                                  serviceDescriptor: ServiceDescriptor,
                                  userFunctionTypeSupport: UserFunctionTypeSupport)
}

class EntityDiscoveryManager(config: EntityDiscoveryManager.Configuration)(
    implicit mat: ActorMaterializer
) extends Actor
    with ActorLogging {

  implicit val system = context.system
  implicit val ec = context.dispatcher
  import EntityDiscoveryManager.Ready

  private[this] final val clientSettings =
    GrpcClientSettings
      .connectToServiceAt(config.userFunctionInterface, config.userFunctionPort)
      .withChannelBuilderOverrides(_.maxInboundMessageSize(config.maxInboundMessageSize.toInt))
      .withTls(false)
  private[this] final val entityDiscoveryClient = EntityDiscoveryClient(clientSettings)
  private[this] final val autoscaler = {
    val autoscalerSettings = AutoscalerSettings(system)
    if (autoscalerSettings.enabled) {
      val managerSettings = ClusterSingletonManagerSettings(system)
      val proxySettings = ClusterSingletonProxySettings(system)

      val scalerFactory: ScalerFactory = (autoscaler, factory) => {
        if (config.devMode) factory.actorOf(Props(new NoScaler(autoscaler)), "noScaler")
        else factory.actorOf(KubernetesDeploymentScaler.props(autoscaler), "kubernetesDeploymentScaler")
      }

      val singleton = context.actorOf(
        ClusterSingletonManager.props(
          Autoscaler.props(autoscalerSettings, scalerFactory, new ClusterMembershipFacadeImpl(Cluster(context.system))),
          terminationMessage = PoisonPill,
          managerSettings
        ),
        "autoscaler"
      )

      context.actorOf(ClusterSingletonProxy.props(singleton.path.toStringWithoutAddress, proxySettings),
                      "autoscalerProxy")
    } else {
      context.actorOf(Props(new NoAutoscaler), "noAutoscaler")
    }
  }
  private[this] final val statsCollector =
    context.actorOf(StatsCollector.props(config.statsCollectorSettings, autoscaler), "statsCollector")
  private[this] final val concurrencyEnforcer =
    context.actorOf(ConcurrencyEnforcer.props(config.concurrencySettings, statsCollector), "concurrencyEnforcer")

  private final val supportFactories: Map[String, UserFunctionTypeSupportFactory] = Map(
      Crdt.name -> new CrdtSupportFactory(context.system,
                                          config,
                                          entityDiscoveryClient,
                                          clientSettings,
                                          concurrencyEnforcer = concurrencyEnforcer,
                                          statsCollector = statsCollector),
      StatelessFunction.name -> new StatelessFunctionSupportFactory(context.system,
                                                                    config,
                                                                    clientSettings,
                                                                    concurrencyEnforcer = concurrencyEnforcer,
                                                                    statsCollector = statsCollector)
    ) ++ {
      if (config.journalEnabled)
        Map(
          EventSourced.name -> new EventSourcedSupportFactory(context.system,
                                                              config,
                                                              clientSettings,
                                                              concurrencyEnforcer = concurrencyEnforcer,
                                                              statsCollector = statsCollector)
        )
      else Map.empty
    }

  entityDiscoveryClient.discover(EntityDiscoveryManager.proxyInfo(supportFactories.keys.toSeq)) pipeTo self

  override def receive: Receive = {
    case spec: EntitySpec =>
      log.info("Received EntitySpec from user function with info: {}", spec.getServiceInfo)

      try {
        val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
        val descriptors = FileDescriptorBuilder.build(descriptorSet)

        if (spec.entities.isEmpty) {
          throw EntityDiscoveryException("No entities were reported by the discover call!")
        }

        val entities = spec.entities.map { entity =>
          val serviceDescriptor = descriptors
            .collectFirst(Function.unlift(descriptor => extractService(entity.serviceName, descriptor)))
            .getOrElse(throw EntityDiscoveryException(s"Service [${entity.serviceName}] not found in descriptors!"))

          supportFactories.get(entity.entityType) match {
            case Some(factory) =>
              EntityDiscoveryManager.ServableEntity(entity.serviceName,
                                                    serviceDescriptor,
                                                    factory.build(entity, serviceDescriptor))
            case None if entity.entityType == EventSourced.name =>
              throw EntityDiscoveryException(
                s"Service [${entity.serviceName}] has declared an event sourced entity, however, this proxy does not have a configured store, or is using a store that doesn't support event sourced journals. A store that supports journals must be configured in this stateful services resource if event sourcing is to be used."
              )
            case None =>
              throw EntityDiscoveryException(
                s"Service [${entity.serviceName}] has declared an unsupported entity type [${entity.entityType}]. Supported types are ${supportFactories.keys
                  .mkString(",")}"
              )
          }
        }

        val router = new UserFunctionRouter(entities, entityDiscoveryClient)

        val eventSupport = EventingManager.createSupport(config.getConfig("eventing"))

        val (route, eventingGraph) =
          Serve.createRoute(entities, router, statsCollector, entityDiscoveryClient, descriptors, eventSupport)

        log.debug("Starting gRPC proxy")

        // Don't actually bind until we have a cluster
        Cluster(context.system).registerOnMemberUp {
          Http().bindAndHandleAsync(
            handler = route,
            interface = config.httpInterface,
            port = config.httpPort,
            connectionContext = HttpConnectionContext(http2 = UseHttp2.Negotiated)
          ) pipeTo self
        }

        // Start warmup
        system.actorOf(Warmup.props(spec.entities.exists(_.entityType == EventSourced.name)), "state-manager-warm-up")

        context.become(binding(eventingGraph))

      } catch {
        case e @ EntityDiscoveryException(message) =>
          entityDiscoveryClient.reportError(UserFunctionError(message))
          throw e
      }

    case Ready => sender ! false
    case Status.Failure(cause) =>
      // Failure to load the entity spec is not fatal, simply crash and let the backoff supervisor restart us
      throw cause
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    val (pkg, name) = Names.splitPrev(serviceName)
    Some(descriptor).filter(_.getPackage == pkg).map(_.findServiceByName(name))
  }

  private[this] final def binding(eventManager: Option[RunnableGraph[Future[Done]]]): Receive = {
    case sb: ServerBinding =>
      log.info(s"CloudState proxy online at ${sb.localAddress}")

      // These can be removed if https://github.com/akka/akka-http/issues/1210 ever gets implemented
      val shutdown = CoordinatedShutdown(system)

      shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
        sb.unbind().map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
        sb.terminate(config.gracefulTerminationTimeout.duration).map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "http-shutdown") { () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
      }

      eventManager.foreach(_.run() pipeTo self)

      context.become(running)

    case Status.Failure(cause) => // Failure to bind the HTTP server is fatal, terminate
      log.error(cause, "Failed to bind HTTP server")
      system.terminate()

    case Ready => sender ! false
  }

  /** Nothing to do when running */
  private[this] final def running: Receive = {
    case Ready =>
      sender ! true
    case Status.Failure(cause) => // Failure in the eventing subsystem, terminate
      log.error(cause, "Eventing failed")
      system.terminate()
    case Done =>
      system.terminate() // FIXME context.become(dead)
  }

  override final def postStop(): Unit =
    entityDiscoveryClient.close()
}

final case class EntityDiscoveryException(message: String) extends RuntimeException(message)
