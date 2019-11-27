package io.cloudstate.proxy.function

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.entity.Entity
import io.cloudstate.protocol.function.StatelessFunctionClient
import io.cloudstate.proxy._
import io.cloudstate.proxy.eventsourced.DynamicLeastShardAllocationStrategy
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

class StatelessFunctionSupportFactory(system: ActorSystem,
                                      config: EntityDiscoveryManager.Configuration,
                                      grpcClientSettings: GrpcClientSettings,
                                      concurrencyEnforcer: ActorRef,
                                      statsCollector: ActorRef)(implicit ec: ExecutionContext, mat: Materializer)
    extends EntityTypeSupportFactory {

  private final val log = Logging.getLogger(system, this.getClass)

  private val statelessFunctionClient = StatelessFunctionClient(grpcClientSettings)

  override def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport = {
    log.debug("Starting StatelessFunction entity for {}", entity.persistenceId)

    val stateManagerConfig = StatelessFunctionEntity.Configuration(entity.serviceName,
                                                                   entity.persistenceId,
                                                                   config.passivationTimeout,
                                                                   config.relayOutputBufferSize)

    val clusterSharding = ClusterSharding(system) // FIXME Should we use ClusterSharding or rely on external LoadBalancer?
    val clusterShardingSettings = ClusterShardingSettings(system)
    val statelessFunctionExecutor = clusterSharding.start(
      typeName = entity.persistenceId,
      entityProps = StatelessFunctionEntitySupervisor.props(statelessFunctionClient,
                                                            stateManagerConfig,
                                                            concurrencyEnforcer,
                                                            statsCollector),
      settings = clusterShardingSettings,
      messageExtractor = new StatelessFunctionEntityIdExtractor(config.numberOfShards),
      allocationStrategy = new DynamicLeastShardAllocationStrategy(1, 10, 2, 0.0),
      handOffStopMessage = StatelessFunctionEntity.Stop
    )

    new StatelessFunctionSupport(statelessFunctionExecutor, config.proxyParallelism, config.relayTimeout)
  }

  private def validate(serviceDescriptor: ServiceDescriptor): Unit = {
    val streamedMethods =
      serviceDescriptor.getMethods.asScala.filter(m => m.toProto.getClientStreaming || m.toProto.getServerStreaming)
    if (streamedMethods.nonEmpty) {
      throw EntityDiscoveryException(
        s"Stateless Function entities do not support streamed methods, but ${serviceDescriptor.getFullName} has the following streamed methods: ${streamedMethods.map(_.getName).mkString(",")}"
      )
    }
  }
}

private final class StatelessFunctionSupport(statelessFunctionExecutor: ActorRef,
                                             parallelism: Int,
                                             private implicit val relayTimeout: Timeout)
    extends EntityTypeSupport {
  import akka.pattern.ask

  override final def handler(method: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    Flow[EntityCommand].mapAsync(parallelism)(handleUnary)

  override final def handleUnary(command: EntityCommand): Future[UserFunctionReply] =
    (statelessFunctionExecutor ? command).mapTo[UserFunctionReply]
}

private final class StatelessFunctionEntityIdExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
  override final def entityId(message: Any): String = message match {
    case command: EntityCommand => command.entityId
  }
}
