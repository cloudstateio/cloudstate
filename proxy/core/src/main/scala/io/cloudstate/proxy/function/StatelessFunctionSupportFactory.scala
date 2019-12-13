package io.cloudstate.proxy.function

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.google.protobuf.Descriptors.ServiceDescriptor
import io.cloudstate.protocol.entity.{ClientAction, Entity}
import io.cloudstate.proxy._
import io.cloudstate.protocol.function._
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

  private final val statelessFunctionClient = StatelessFunctionClient(grpcClientSettings)

  override def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport = {
    log.debug("Starting StatelessFunction entity for {}", entity.persistenceId)

    validate(serviceDescriptor)

    new StatelessFunctionSupport(entity.serviceName,
                                 statelessFunctionClient,
                                 config.proxyParallelism,
                                 config.relayTimeout,
                                 ec)
  }

  private[this] final def validate(serviceDescriptor: ServiceDescriptor): Unit = {
    val streamedMethods =
      serviceDescriptor.getMethods.asScala.filter(m => m.toProto.getClientStreaming || m.toProto.getServerStreaming)
    if (streamedMethods.nonEmpty) {
      throw EntityDiscoveryException(
        s"Stateless Function entities do not support streamed methods, but ${serviceDescriptor.getFullName} has the following streamed methods: ${streamedMethods.map(_.getName).mkString(",")}"
      )
    }
  }
}

private final class StatelessFunctionSupport(serviceName: String,
                                             statelessFunctionClient: StatelessFunctionClient,
                                             parallelism: Int,
                                             private implicit val relayTimeout: Timeout,
                                             private implicit val ec: ExecutionContext)
    extends EntityTypeSupport {
  import akka.pattern.ask

  override final def handler(method: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed] =
    Flow[EntityCommand].mapAsync(parallelism)(handleUnary)

  override final def handleUnary(entityCommand: EntityCommand): Future[UserFunctionReply] =
    if (entityCommand.streamed) {
      throw new IllegalStateException("Request streaming is not yet supported for StatelessFunctions")
    } else {
      statelessFunctionClient
        .handleUnary(
          FunctionCommand(
            serviceName = serviceName,
            name = entityCommand.name,
            payload = entityCommand.payload
          )
        )
        .map(reply => {
          import FunctionReply.Response
          import ClientAction.Action
          UserFunctionReply(
            clientAction = Some(ClientAction(reply.response match {
              case Response.Reply(r) => Action.Reply(r)
              case Response.Failure(f) => Action.Failure(f)
              case Response.Forward(f) => Action.Forward(f)
              case Response.Empty => Action.Empty
            })),
            sideEffects = reply.sideEffects
          )
        })
    }
}
