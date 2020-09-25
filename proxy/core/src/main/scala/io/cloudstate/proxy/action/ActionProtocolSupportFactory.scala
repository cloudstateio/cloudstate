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

package io.cloudstate.proxy.action

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import io.cloudstate.protocol.action._
import io.cloudstate.protocol.entity.{ClientAction, Entity, Metadata}
import io.cloudstate.proxy._
import io.cloudstate.proxy.entity.UserFunctionReply

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

class ActionProtocolSupportFactory(
    system: ActorSystem,
    config: EntityDiscoveryManager.Configuration,
    grpcClientSettings: GrpcClientSettings
)(implicit ec: ExecutionContext, mat: Materializer)
    extends UserFunctionTypeSupportFactory {

  private final val log = Logging.getLogger(system, this.getClass)

  private final val actionProtocolClient = ActionProtocolClient(grpcClientSettings)(system)

  override def build(entity: Entity, serviceDescriptor: ServiceDescriptor): UserFunctionTypeSupport = {
    log.debug("Starting Action for {}", entity.persistenceId)

    val methodDescriptors = serviceDescriptor.getMethods.asScala.map { method =>
      method.getName -> method
    }.toMap

    new ActionProtocolSupport(entity.serviceName,
                              methodDescriptors,
                              actionProtocolClient,
                              config.proxyParallelism,
                              config.relayTimeout,
                              ec)
  }
}

private final class ActionProtocolSupport(serviceName: String,
                                          methodDescriptors: Map[String, MethodDescriptor],
                                          actionProtocolClient: ActionProtocolClient,
                                          parallelism: Int,
                                          private implicit val relayTimeout: Timeout,
                                          private implicit val ec: ExecutionContext)
    extends UserFunctionTypeSupport {

  private def methodDescriptor(name: String): MethodDescriptor =
    methodDescriptors.getOrElse(name, throw EntityDiscoveryException(s"Unknown command $name on service $serviceName"))

  private def unaryFlow(commandName: String, metadata: Metadata) =
    Flow[UserFunctionRouter.Message]
      .mapAsync(1)(
        message => handleUnary(commandName, UserFunctionTypeSupport.mergeStreamLevelMetadata(metadata, message))
      )

  private def streamOutFlow(commandName: String, metadata: Metadata) =
    Flow[UserFunctionRouter.Message]
      .flatMapConcat(
        message =>
          actionProtocolClient
            .handleStreamedOut(
              convertUnaryIn(commandName, UserFunctionTypeSupport.mergeStreamLevelMetadata(metadata, message))
            )
            .map(functionReplyToUserFunctionReply)
      )

  private def streamInFlow(commandName: String, metadata: Metadata) =
    sourceToSourceToFlow((in: Source[UserFunctionRouter.Message, NotUsed]) => {
      Source.future(actionProtocolClient.handleStreamedIn(convertStreamIn(commandName, metadata, in)))
    }).map(functionReplyToUserFunctionReply)

  private def streamedFlow(commandName: String, metadata: Metadata) =
    sourceToSourceToFlow(
      (in: Source[UserFunctionRouter.Message, NotUsed]) =>
        actionProtocolClient.handleStreamed(convertStreamIn(commandName, metadata, in))
    ).map(functionReplyToUserFunctionReply)

  private def convertStreamIn(commandName: String,
                              metadata: Metadata,
                              in: Source[UserFunctionRouter.Message, NotUsed]): Source[ActionCommand, NotUsed] =
    Source
      .single(
        ActionCommand(
          serviceName = serviceName,
          name = commandName,
          metadata = Some(metadata)
        )
      )
      .concat(
        in.map { message =>
          ActionCommand(
            payload = Some(message.payload),
            metadata = Some(message.metadata)
          )
        }
      )

  override def handler(commandName: String,
                       metadata: Metadata): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed] = {
    val method = methodDescriptor(commandName)
    val streamIn = method.isClientStreaming
    val streamOut = method.isServerStreaming
    if (streamIn && streamOut) streamedFlow(commandName, metadata)
    else if (streamIn) streamInFlow(commandName, metadata)
    else if (streamOut) streamOutFlow(commandName, metadata)
    else unaryFlow(commandName, metadata)
  }

  private def functionReplyToUserFunctionReply(reply: ActionResponse): UserFunctionReply = {
    import ActionResponse.Response
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

  private def convertUnaryIn(commandName: String, message: UserFunctionRouter.Message): ActionCommand =
    ActionCommand(
      serviceName = serviceName,
      name = commandName,
      payload = Some(message.payload),
      metadata = Some(message.metadata)
    )

  override def handleUnary(commandName: String, message: UserFunctionRouter.Message): Future[UserFunctionReply] =
    actionProtocolClient
      .handleUnary(convertUnaryIn(commandName, message))
      .map(functionReplyToUserFunctionReply)

  private def sourceToSourceToFlow[In, Out, MOut](f: Source[In, NotUsed] => Source[Out, MOut]): Flow[In, Out, NotUsed] =
    Flow[In].prefixAndTail(0).flatMapConcat { case (Nil, in) => f(in) }

}
