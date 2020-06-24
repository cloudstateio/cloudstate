package io.cloudstate.proxy.function

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
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

  private final val statelessFunctionClient = StatelessFunctionClient(grpcClientSettings)(system)

  override def buildEntityTypeSupport(entity: Entity,
                                      serviceDescriptor: ServiceDescriptor,
                                      methodDescriptors: Map[String, EntityMethodDescriptor]): EntityTypeSupport = {
    log.debug("Starting StatelessFunction entity for {}", entity.persistenceId)

    validate(serviceDescriptor, methodDescriptors)

    new StatelessFunctionSupport(entity.serviceName,
                                 statelessFunctionClient,
                                 config.proxyParallelism,
                                 config.relayTimeout,
                                 ec)
  }

  private[this] final def validate(serviceDescriptor: ServiceDescriptor,
                                   methodDescriptors: Map[String, EntityMethodDescriptor]): Unit = ()
}

private final class StatelessFunctionSupport(serviceName: String,
                                             statelessFunctionClient: StatelessFunctionClient,
                                             parallelism: Int,
                                             private implicit val relayTimeout: Timeout,
                                             private implicit val ec: ExecutionContext)
    extends EntityTypeSupport {
  import akka.pattern.ask

  private final val unaryFlow =
    Flow[EntityCommand].mapAsync(1)(handleUnary)

  private final val streamOutFlow =
    Flow[EntityCommand]
      .flatMapConcat(
        ec =>
          statelessFunctionClient
            .handleStreamedOut(entityCommandToFunctionCommand(ec))
            .map(functionReplyToUserFunctionReply)
      )

  private final val streamInFlow =
    Flow
      .setup[EntityCommand, UserFunctionReply, NotUsed] { (mat, attr) =>
        implicit val materializer = mat

        val (outSubscriber, outSource) = Source.asSubscriber[FunctionCommand].preMaterialize()
        val outSink = Flow[EntityCommand].map(entityCommandToFunctionCommand).to(Sink.fromSubscriber(outSubscriber))

        val inSource =
          Source.fromFuture(statelessFunctionClient.handleStreamedIn(outSource)).map(functionReplyToUserFunctionReply)

        Flow.fromSinkAndSource(outSink, inSource)
      }
      .mapMaterializedValue(_ => NotUsed)

  private final val streamedFlow = Flow
    .setup[EntityCommand, UserFunctionReply, NotUsed] { (mat, attr) =>
      implicit val materializer = mat

      val (outSubscriber, outSource) = Source.asSubscriber[FunctionCommand].preMaterialize()
      val outSink = Flow[EntityCommand].map(entityCommandToFunctionCommand).to(Sink.fromSubscriber(outSubscriber))

      val (publisher, inSink) = Sink.asPublisher[FunctionReply](false).preMaterialize()
      val inSource = Source.fromPublisher(publisher).map(functionReplyToUserFunctionReply)

      statelessFunctionClient.handleStreamed(outSource).runWith(inSink)
      Flow.fromSinkAndSource(outSink, inSource)
    }
    .mapMaterializedValue(_ => NotUsed)

  override final def handler(method: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed] = {
    val streamIn = method.method.isClientStreaming
    val streamOut = method.method.isServerStreaming
    if (streamIn && streamOut) streamedFlow
    else if (streamIn) streamInFlow
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

  private def entityCommandToFunctionCommand(command: EntityCommand): FunctionCommand =
    FunctionCommand(
      serviceName = serviceName,
      name = command.name,
      payload = command.payload
    )

  override final def handleUnary(entityCommand: EntityCommand): Future[UserFunctionReply] =
    if (entityCommand.streamed) {
      throw new IllegalStateException("Request streaming is not yet supported for StatelessFunctions")
    } else {
      statelessFunctionClient
        .handleUnary(entityCommandToFunctionCommand(entityCommand))
        .map(functionReplyToUserFunctionReply)
    }
}
