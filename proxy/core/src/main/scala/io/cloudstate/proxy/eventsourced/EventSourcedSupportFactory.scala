package io.cloudstate.proxy.eventsourced

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
import io.cloudstate.entity.Entity
import io.cloudstate.eventsourced.EventSourcedClient
import io.cloudstate.proxy._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

class EventSourcedSupportFactory(system: ActorSystem, config: EntityDiscoveryManager.Configuration,
  grpcClientSettings: GrpcClientSettings, concurrencyEnforcer: ActorRef, statsCollector: ActorRef)(implicit ec: ExecutionContext, mat: Materializer) extends EntityTypeSupportFactory {

  private final val log = Logging.getLogger(system, this.getClass)

  private val eventSourcedClient = EventSourcedClient(grpcClientSettings)

  override def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport = {
    val stateManagerConfig = EventSourcedEntity.Configuration(entity.serviceName, entity.persistenceId, config.passivationTimeout, config.relayOutputBufferSize)

    log.debug("Starting EventSourcedEntity for {}", entity.persistenceId)
    val clusterSharding = ClusterSharding(system)
    val clusterShardingSettings = ClusterShardingSettings(system)
    val eventSourcedEntity = clusterSharding.start(
      typeName = entity.persistenceId,
      entityProps = EventSourcedEntitySupervisor.props(eventSourcedClient, stateManagerConfig, concurrencyEnforcer, statsCollector),
      settings = clusterShardingSettings,
      messageExtractor = new EntityIdExtractor(config.numberOfShards),
      allocationStrategy = new DynamicLeastShardAllocationStrategy(1, 10, 2, 0.0),
      handOffStopMessage = EventSourcedEntity.Stop
    )

    new EventSourcedSupport(eventSourcedEntity, config.proxyParallelism, config.relayTimeout)
  }

  private def validate(serviceDescriptor: ServiceDescriptor): Unit = {
    val streamedMethods = serviceDescriptor.getMethods.asScala.filter(m => m.toProto.getClientStreaming || m.toProto.getServerStreaming)
    if (streamedMethods.nonEmpty) {
      throw EntityDiscoveryException(s"Event sourced entities do not support streamed methods, but ${serviceDescriptor.getFullName} has the following streamed methods: ${streamedMethods.map(_.getName).mkString(",")}")
    }
  }
}

private class EventSourcedSupport(eventSourcedEntity: ActorRef, parallelism: Int, private implicit val relayTimeout: Timeout) extends EntityTypeSupport {
  import akka.pattern.ask

  override def handler(method: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    Flow[EntityCommand].mapAsync(parallelism)(command => (eventSourcedEntity ? command).mapTo[UserFunctionReply])

  override def handleUnary(command: EntityCommand): Future[UserFunctionReply] =
    (eventSourcedEntity ? command).mapTo[UserFunctionReply]
}

private final class EntityIdExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
  override final def entityId(message: Any): String = message match {
    case command: EntityCommand => command.entityId
  }
}

