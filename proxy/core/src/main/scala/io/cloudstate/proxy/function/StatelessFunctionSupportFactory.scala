package io.cloudstate.proxy.function

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import io.cloudstate.protocol.entity.{ClientAction, Entity}
import io.cloudstate.proxy._
import io.cloudstate.protocol.function._
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

class StatelessFunctionSupportFactory(system: ActorSystem,
                                      config: EntityDiscoveryManager.Configuration,
                                      grpcClientSettings: GrpcClientSettings,
                                      concurrencyEnforcer: ActorRef,
                                      statsCollector: ActorRef)(implicit ec: ExecutionContext, mat: Materializer)
    extends UserFunctionTypeSupportFactory {

  private final val log = Logging.getLogger(system, this.getClass)

  private final val statelessFunctionClient = StatelessFunctionClient(grpcClientSettings)

  override def build(entity: Entity, serviceDescriptor: ServiceDescriptor): UserFunctionTypeSupport = {
    log.debug("Starting StatelessFunction entity for {}", entity.persistenceId)

    val methodDescriptors = serviceDescriptor.getMethods.asScala.map { method =>
      method.getName -> method
    }.toMap

    new StatelessFunctionSupport(entity.serviceName,
                                 methodDescriptors,
                                 statelessFunctionClient,
                                 config.proxyParallelism,
                                 config.relayTimeout,
                                 ec)
  }
}

private final class StatelessFunctionSupport(serviceName: String,
                                             methodDescriptors: Map[String, MethodDescriptor],
                                             statelessFunctionClient: StatelessFunctionClient,
                                             parallelism: Int,
                                             private implicit val relayTimeout: Timeout,
                                             private implicit val ec: ExecutionContext)
    extends UserFunctionTypeSupport {

  private def methodDescriptor(name: String): MethodDescriptor =
    methodDescriptors.getOrElse(name, throw EntityDiscoveryException(s"Unknown command $name on service $serviceName"))

  private final val unaryFlow =
    Flow[UserFunctionCommand].mapAsync(1)(handleUnary)

  private final val streamOutFlow =
    Flow[UserFunctionCommand]
      .flatMapConcat(
        ufc =>
          statelessFunctionClient
            .handleStreamedOut(convertUnaryIn(ufc))
            .map(functionReplyToUserFunctionReply)
      )

  private def streamInFlow(command: String) =
    sourceToSourceToFlow((in: Source[UserFunctionCommand, NotUsed]) => {
      Source.fromFuture(statelessFunctionClient.handleStreamedIn(convertStreamIn(command, in)))
    }).map(functionReplyToUserFunctionReply)

  private def streamedFlow(command: String) =
    sourceToSourceToFlow(
      (in: Source[UserFunctionCommand, NotUsed]) => statelessFunctionClient.handleStreamed(convertStreamIn(command, in))
    ).map(functionReplyToUserFunctionReply)

  private def convertStreamIn(command: String,
                              in: Source[UserFunctionCommand, NotUsed]): Source[FunctionCommand, NotUsed] =
    Source
      .single(
        FunctionCommand(
          serviceName = serviceName,
          name = command
        )
      )
      .concat(
        in.map { cmd =>
          FunctionCommand(
            payload = cmd.payload
          )
        }
      )

  override def handler(command: String): Flow[UserFunctionCommand, UserFunctionReply, NotUsed] = {
    val method = methodDescriptor(command)
    val streamIn = method.isClientStreaming
    val streamOut = method.isServerStreaming
    if (streamIn && streamOut) streamedFlow(command)
    else if (streamIn) streamInFlow(command)
    else if (streamOut) streamOutFlow
    else unaryFlow
  }

  private def functionReplyToUserFunctionReply(reply: FunctionReply): UserFunctionReply = {
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
  }

  private def convertUnaryIn(command: UserFunctionCommand): FunctionCommand =
    FunctionCommand(
      serviceName = serviceName,
      name = command.name,
      payload = command.payload,
      metadata = command.metadata
    )

  override final def handleUnary(userFunctionCommand: UserFunctionCommand): Future[UserFunctionReply] =
    statelessFunctionClient
      .handleUnary(convertUnaryIn(userFunctionCommand))
      .map(functionReplyToUserFunctionReply)

  private def sourceToSourceToFlow[In, Out, MOut](f: Source[In, NotUsed] => Source[Out, MOut]): Flow[In, Out, NotUsed] =
    Flow[In].prefixAndTail(0).flatMapConcat { case (Nil, in) => f(in) }

}
