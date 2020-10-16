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

package io.cloudstate.proxy.crud

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
import io.cloudstate.protocol.crud.CrudClient
import io.cloudstate.protocol.entity.{Entity, Metadata}
import io.cloudstate.proxy._
import io.cloudstate.proxy.crud.store.{JdbcRepositoryImpl, JdbcStoreSupport}
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.concurrent.{ExecutionContext, Future}

class CrudSupportFactory(system: ActorSystem,
                         config: EntityDiscoveryManager.Configuration,
                         grpcClientSettings: GrpcClientSettings)(implicit ec: ExecutionContext, mat: Materializer)
    extends EntityTypeSupportFactory
    with JdbcStoreSupport {

  private final val log = Logging.getLogger(system, this.getClass)

  private val crudClient = CrudClient(grpcClientSettings)(system)

  override def buildEntityTypeSupport(entity: Entity,
                                      serviceDescriptor: ServiceDescriptor,
                                      methodDescriptors: Map[String, EntityMethodDescriptor]): EntityTypeSupport = {
    validate(serviceDescriptor, methodDescriptors)

    val stateManagerConfig = CrudEntity.Configuration(entity.serviceName,
                                                      entity.persistenceId,
                                                      config.passivationTimeout,
                                                      config.relayOutputBufferSize)

    val repository = new JdbcRepositoryImpl(createStore(config.config))

    log.debug("Starting CrudEntity for {}", entity.persistenceId)
    val clusterSharding = ClusterSharding(system)
    val clusterShardingSettings = ClusterShardingSettings(system)
    val crudEntity = clusterSharding.start(
      typeName = entity.persistenceId,
      entityProps = CrudEntitySupervisor.props(crudClient, stateManagerConfig, repository),
      settings = clusterShardingSettings,
      messageExtractor = new CrudEntityIdExtractor(config.numberOfShards),
      allocationStrategy = new DynamicLeastShardAllocationStrategy(1, 10, 2, 0.0),
      handOffStopMessage = CrudEntity.Stop
    )

    new CrudSupport(crudEntity, config.proxyParallelism, config.relayTimeout)
  }

  private def validate(serviceDescriptor: ServiceDescriptor,
                       methodDescriptors: Map[String, EntityMethodDescriptor]): Unit = {
    val streamedMethods =
      methodDescriptors.values.filter(m => m.method.toProto.getClientStreaming || m.method.toProto.getServerStreaming)
    if (streamedMethods.nonEmpty) {
      val offendingMethods = streamedMethods.map(_.method.getName).mkString(",")
      throw EntityDiscoveryException(
        s"CRUD entities do not support streamed methods, but ${serviceDescriptor.getFullName} has the following streamed methods: ${offendingMethods}"
      )
    }
    val methodsWithoutKeys = methodDescriptors.values.filter(_.keyFieldsCount < 1)
    if (methodsWithoutKeys.nonEmpty) {
      val offendingMethods = methodsWithoutKeys.map(_.method.getName).mkString(",")
      throw EntityDiscoveryException(
        s"""CRUD entities do not support methods whose parameters do not have at least one field marked as entity_key,
            |but ${serviceDescriptor.getFullName} has the following methods without keys: $offendingMethods""".stripMargin
          .replaceAll("\n", " ")
      )
    }
  }
}

private class CrudSupport(crudEntity: ActorRef, parallelism: Int, private implicit val relayTimeout: Timeout)
    extends EntityTypeSupport {
  import akka.pattern.ask

  override def handler(method: EntityMethodDescriptor,
                       metadata: Metadata): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    Flow[EntityCommand].mapAsync(parallelism)(
      command =>
        (crudEntity ? EntityTypeSupport.mergeStreamLevelMetadata(metadata, command))
          .mapTo[UserFunctionReply]
    )

  override def handleUnary(command: EntityCommand): Future[UserFunctionReply] =
    (crudEntity ? command).mapTo[UserFunctionReply]
}

private final class CrudEntityIdExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
  override final def entityId(message: Any): String = message match {
    case command: EntityCommand => command.entityId
  }
}
