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

package io.cloudstate.proxy.crdt

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.cluster.ddata.DistributedData
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.crdt.CrdtClient
import io.cloudstate.protocol.entity.{Entity, EntityDiscovery, Metadata}
import io.cloudstate.proxy._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CrdtSupportFactory(system: ActorSystem,
                         config: EntityDiscoveryManager.Configuration,
                         discovery: EntityDiscovery,
                         grpcClientSettings: GrpcClientSettings)(implicit ec: ExecutionContext, mat: Materializer)
    extends EntityTypeSupportFactory {

  private[this] final val log = Logging.getLogger(system, this.getClass)

  private[this] final val crdtClient = CrdtClient(grpcClientSettings)(system)

  override def buildEntityTypeSupport(entity: Entity,
                                      serviceDescriptor: ServiceDescriptor,
                                      methodDescriptors: Map[String, EntityMethodDescriptor]): EntityTypeSupport = {

    validate(serviceDescriptor, methodDescriptors)

    val crdtEntityConfig = CrdtEntity.Configuration(entity.serviceName,
                                                    entity.persistenceId,
                                                    config.passivationTimeout,
                                                    config.relayOutputBufferSize,
                                                    3.seconds,
                                                    5.seconds)

    log.debug("Starting CrdtEntity for {}", entity.serviceName)

    val crdtEntityProps = CrdtEntity.props(crdtClient, crdtEntityConfig, discovery)
    val crdtEntityManager =
      system.actorOf(CrdtEntityManager.props(crdtEntityProps), URLEncoder.encode(entity.serviceName, UTF_8.toString)) // toString is needed for Java 8 compatibility, as Charset method added post Java 8

    // Ensure the ddata replicator is started, to ensure state replication starts immediately, and also ensure the first
    // request to the first CRDT doesn't timeout
    DistributedData(system)

    val coordinatedShutdown = CoordinatedShutdown(system)
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseClusterShardingShutdownRegion,
                                "shutdown-crdt-" + entity.serviceName) { () =>
      implicit val timeout = Timeout(10.seconds)
      (crdtEntityManager ? CrdtEntityManager.Shutdown).mapTo[Done]
    }

    new CrdtSupport(crdtEntityManager, config.proxyParallelism, config.relayTimeout)
  }

  private def validate(serviceDescriptor: ServiceDescriptor,
                       methodDescriptors: Map[String, EntityMethodDescriptor]): Unit = {
    val streamedMethods = methodDescriptors.values.filter(m => m.method.toProto.getClientStreaming)
    if (streamedMethods.nonEmpty) {
      val offendingMethods = streamedMethods.map(_.method.getName).mkString(",")
      throw EntityDiscoveryException(
        s"CRDT entities do not support streaming in from the client, but ${serviceDescriptor.getFullName} has the following streamed methods: ${offendingMethods}"
      )
    }
    val methodsWithoutKeys = methodDescriptors.values.filter(_.keyFieldsCount < 1)
    if (methodsWithoutKeys.nonEmpty) {
      val offendingMethods = methodsWithoutKeys.map(_.method.getName).mkString(",")
      throw new EntityDiscoveryException(
        s"CRDT entities do not support methods whose parameters do not have at least one field marked as entity_key, " +
        "but ${serviceDescriptor.getFullName} has the following methods without keys: ${offendingMethods}"
      )
    }
  }
}

private class CrdtSupport(crdtEntity: ActorRef, parallelism: Int, private implicit val relayTimeout: Timeout)
    extends EntityTypeSupport {
  import akka.pattern.ask

  override def handler(method: EntityMethodDescriptor,
                       metadata: Metadata): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    if (method.method.toProto.getServerStreaming) {
      Flow[EntityCommand]
        .mapAsync(parallelism)(
          command =>
            (crdtEntity ? EntityTypeSupport.mergeStreamLevelMetadata(metadata, command))
              .mapTo[Source[UserFunctionReply, NotUsed]]
        )
        .flatMapConcat(identity)
    } else {
      Flow[EntityCommand].mapAsync(parallelism)(
        command => handleUnary(EntityTypeSupport.mergeStreamLevelMetadata(metadata, command))
      )
    }

  override def handleUnary(command: EntityCommand): Future[UserFunctionReply] =
    (crdtEntity ? command).mapTo[UserFunctionReply]
}

final case class StreamedCrdtCommand(command: EntityCommand)
