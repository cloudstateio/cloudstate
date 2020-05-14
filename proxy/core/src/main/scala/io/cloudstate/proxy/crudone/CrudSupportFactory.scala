package io.cloudstate.proxy.crudone

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.crud_one.CrudOneClient
import io.cloudstate.protocol.entity.Entity
import io.cloudstate.proxy._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.eventsourced.DynamicLeastShardAllocationStrategy

import scala.concurrent.{ExecutionContext, Future}

class CrudSupportFactory(system: ActorSystem,
                         config: EntityDiscoveryManager.Configuration,
                         grpcClientSettings: GrpcClientSettings,
                         concurrencyEnforcer: ActorRef,
                         statsCollector: ActorRef)(implicit ec: ExecutionContext, mat: Materializer)
    extends EntityTypeSupportFactory {

  private final val log = Logging.getLogger(system, this.getClass)

  private val crudClient = CrudOneClient(grpcClientSettings)

  override def buildEntityTypeSupport(entity: Entity,
                                      serviceDescriptor: ServiceDescriptor,
                                      methodDescriptors: Map[String, EntityMethodDescriptor]): EntityTypeSupport = {
    validate(serviceDescriptor, methodDescriptors)

    val stateManagerConfig = CrudEntity.Configuration(entity.serviceName,
                                                      entity.persistenceId,
                                                      config.passivationTimeout,
                                                      config.relayOutputBufferSize)

    log.debug("Starting EventSourcedEntity for {}", entity.persistenceId)
    val clusterSharding = ClusterSharding(system)
    val clusterShardingSettings = ClusterShardingSettings(system)
    val eventSourcedEntity = clusterSharding.start(
      typeName = entity.persistenceId,
      entityProps = CrudEntitySupervisor.props(crudClient, stateManagerConfig, concurrencyEnforcer, statsCollector),
      settings = clusterShardingSettings,
      messageExtractor = new EntityIdExtractor(config.numberOfShards),
      allocationStrategy = new DynamicLeastShardAllocationStrategy(1, 10, 2, 0.0),
      handOffStopMessage = CrudEntity.Stop
    )

    new EventSourcedSupport(eventSourcedEntity, config.proxyParallelism, config.relayTimeout)
  }

  private def validate(serviceDescriptor: ServiceDescriptor,
                       methodDescriptors: Map[String, EntityMethodDescriptor]): Unit = {
    val streamedMethods =
      methodDescriptors.values.filter(m => m.method.toProto.getClientStreaming || m.method.toProto.getServerStreaming)
    if (streamedMethods.nonEmpty) {
      val offendingMethods = streamedMethods.map(_.method.getName).mkString(",")
      throw EntityDiscoveryException(
        s"Event sourced entities do not support streamed methods, but ${serviceDescriptor.getFullName} has the following streamed methods: ${offendingMethods}"
      )
    }
    val methodsWithoutKeys = methodDescriptors.values.filter(_.keyFieldsCount < 1)
    if (methodsWithoutKeys.nonEmpty) {
      val offendingMethods = methodsWithoutKeys.map(_.method.getName).mkString(",")
      throw new EntityDiscoveryException(
        s"Event sourced entities do not support methods whose parameters do not have at least one field marked as entity_key, " +
        "but ${serviceDescriptor.getFullName} has the following methods without keys: ${offendingMethods}"
      )
    }
  }
}

private class EventSourcedSupport(eventSourcedEntity: ActorRef,
                                  parallelism: Int,
                                  private implicit val relayTimeout: Timeout)
    extends EntityTypeSupport {
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
