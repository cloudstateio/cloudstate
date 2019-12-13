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
import io.cloudstate.protocol.entity.{Entity, EntityDiscovery}
import io.cloudstate.proxy._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CrdtSupportFactory(system: ActorSystem,
                         config: EntityDiscoveryManager.Configuration,
                         discovery: EntityDiscovery,
                         grpcClientSettings: GrpcClientSettings,
                         concurrencyEnforcer: ActorRef,
                         statsCollector: ActorRef)(implicit ec: ExecutionContext, mat: Materializer)
    extends EntityTypeSupportFactory {

  private[this] final val log = Logging.getLogger(system, this.getClass)

  private[this] final val crdtClient = CrdtClient(grpcClientSettings)

  override def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport = {

    validate(serviceDescriptor)

    val crdtEntityConfig = CrdtEntity.Configuration(entity.serviceName,
                                                    entity.persistenceId,
                                                    config.passivationTimeout,
                                                    config.relayOutputBufferSize,
                                                    3.seconds,
                                                    5.seconds)

    log.debug("Starting CrdtEntity for {}", entity.serviceName)

    val crdtEntityProps = CrdtEntity.props(crdtClient, crdtEntityConfig, discovery)
    val crdtEntityManager =
      system.actorOf(CrdtEntityManager.props(crdtEntityProps), URLEncoder.encode(entity.serviceName, UTF_8))

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

  private def validate(serviceDescriptor: ServiceDescriptor): Unit = {
    val streamedMethods = serviceDescriptor.getMethods.asScala.filter(m => m.toProto.getClientStreaming)
    if (streamedMethods.nonEmpty) {
      throw EntityDiscoveryException(
        s"CRDT entities do not support streaming in from the client, but ${serviceDescriptor.getFullName} has the following streamed methods: ${streamedMethods.map(_.getName).mkString(",")}"
      )
    }
  }
}

private class CrdtSupport(crdtEntity: ActorRef, parallelism: Int, private implicit val relayTimeout: Timeout)
    extends EntityTypeSupport {
  import akka.pattern.ask

  override def handler(method: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    if (method.method.toProto.getServerStreaming) {
      Flow[EntityCommand]
        .mapAsync(parallelism)(command => (crdtEntity ? command).mapTo[Source[UserFunctionReply, NotUsed]])
        .flatMapConcat(identity)
    } else {
      Flow[EntityCommand].mapAsync(parallelism)(handleUnary)
    }

  override def handleUnary(command: EntityCommand): Future[UserFunctionReply] =
    (crdtEntity ? command).mapTo[UserFunctionReply]
}

final case class StreamedCrdtCommand(command: EntityCommand)
