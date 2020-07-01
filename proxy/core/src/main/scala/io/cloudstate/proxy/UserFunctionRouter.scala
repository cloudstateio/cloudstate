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

package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.cloudstate.protocol.entity.{ClientAction, EntityDiscovery, Forward, Metadata, SideEffect, UserFunctionError}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.UserFunctionReply
import com.google.protobuf.any.{Any => ScalaPbAny}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class UserFunctionRouter(val entities: Seq[ServableEntity], entityDiscovery: EntityDiscovery)(
    implicit mat: Materializer,
    ec: ExecutionContext
) {

  private[this] final val entityCommands = entities.map {
    case ServableEntity(serviceName, serviceDescriptor, entitySupport) =>
      serviceName -> EntityCommands(serviceName,
                                    entitySupport,
                                    serviceDescriptor.getMethods.asScala.map(_.getName).toSet)
  }.toMap

  final def handle(serviceName: String,
                   command: String,
                   metadata: Metadata): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed] =
    routeStream(Nil, RouteReason.Initial, serviceName, command, metadata)

  final def handleUnary(serviceName: String,
                        command: String,
                        message: UserFunctionRouter.Message): Future[UserFunctionReply] =
    routeMessageUnary(Nil, RouteReason.Initial, serviceName, command, message)

  private final def route(
      trace: List[(RouteReason, String, String)]
  ): Flow[UserFunctionReply, UserFunctionReply, NotUsed] =
    Flow[UserFunctionReply].flatMapConcat { response =>
      val sideEffects = Source(response.sideEffects.toList)
        .flatMapConcat {
          case SideEffect(serviceName, commandName, payload, synchronous, metadata, _) =>
            routeMessage(
              trace,
              RouteReason.SideEffect,
              serviceName,
              commandName,
              payload.getOrElse(ScalaPbAny.defaultInstance),
              synchronous,
              metadata.getOrElse(Metadata.defaultInstance)
            )
        }

      val nextAction = response.clientAction match {
        case Some(
            ClientAction(ClientAction.Action.Forward(Forward(serviceName, commandName, payload, metadata, _)), _)
            ) =>
          routeMessage(
            trace,
            RouteReason.Forwarded,
            serviceName,
            commandName,
            payload.getOrElse(ScalaPbAny.defaultInstance),
            synchronous = true,
            metadata.getOrElse(Metadata.defaultInstance)
          )
        case None | Some(ClientAction(ClientAction.Action.Empty, _)) =>
          Source.empty
        case _ =>
          Source.single(response)
      }

      // First do the side effects, but ignore the response, then do the next action
      sideEffects
        .filter(_ => false)
        .concat(nextAction)
    }

  private final def routeUnary(trace: List[(RouteReason, String, String)],
                               response: UserFunctionReply): Future[UserFunctionReply] =
    response.sideEffects.foldLeft(Future.unit: Future[Any]) { (future, sideEffect) =>
      future.flatMap { _ =>
        val sideEffectFuture = routeMessageUnary(
          trace,
          RouteReason.SideEffect,
          sideEffect.serviceName,
          sideEffect.commandName,
          UserFunctionRouter.Message(sideEffect.payload.getOrElse(ScalaPbAny.defaultInstance),
                                     sideEffect.metadata.getOrElse(Metadata.defaultInstance))
        )
        if (sideEffect.synchronous) {
          sideEffectFuture
        } else {
          future
        }
      }
    } flatMap { _ =>
      response.clientAction match {
        case Some(
            ClientAction(ClientAction.Action.Forward(Forward(serviceName, commandName, payload, metadata, _)), _)
            ) =>
          routeMessageUnary(
            trace,
            RouteReason.Forwarded,
            serviceName,
            commandName,
            UserFunctionRouter.Message(payload.getOrElse(ScalaPbAny.defaultInstance),
                                       metadata.getOrElse(Metadata.defaultInstance))
          )
        case _ =>
          Future.successful(response)
      }
    }

  private final def routeMessage(trace: List[(RouteReason, String, String)],
                                 routeReason: RouteReason,
                                 serviceName: String,
                                 commandName: String,
                                 payload: com.google.protobuf.any.Any,
                                 synchronous: Boolean,
                                 metadata: Metadata): Source[UserFunctionReply, NotUsed] = {

    val flow = routeStream(trace, routeReason, serviceName, commandName, metadata)

    val source = Source.single(UserFunctionRouter.Message(payload, metadata)) via flow

    if (synchronous) {
      // Return the source as is so that it gets executed as part of the main flow
      source
    } else {
      // This side effect is not synchronous, so we run it asynchronously and ignore the result, and return
      // nothing to do
      source.runWith(Sink.ignore) // TODO: investigate the risk of congestion here
      Source.empty
    }
  }

  private final def routeStream(
      trace: List[(RouteReason, String, String)],
      routeReason: RouteReason,
      serviceName: String,
      commandName: String,
      metadata: Metadata
  ): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed] =
    entityCommands.get(serviceName) match {
      case Some(EntityCommands(_, entitySupport, commands)) =>
        if (commands(commandName)) {
          entitySupport
            .handler(commandName, metadata)
            .via(route((routeReason, serviceName, commandName) :: trace))
        } else {
          reportErrorFlow(routeReason, trace, s"Service [$serviceName] does not have a command named: [$commandName]")
        }
      case None =>
        reportErrorFlow(routeReason, trace, s"Service [$serviceName] unknown")
    }

  private final def routeMessageUnary(trace: List[(RouteReason, String, String)],
                                      routeReason: RouteReason,
                                      serviceName: String,
                                      commandName: String,
                                      message: UserFunctionRouter.Message): Future[UserFunctionReply] =
    entityCommands.get(serviceName) match {
      case Some(EntityCommands(_, entitySupport, commands)) =>
        if (commands(commandName)) {
          entitySupport.handleUnary(commandName, message).flatMap { result =>
            routeUnary((routeReason, serviceName, commandName) :: trace, result)
          }
        } else {
          reportErrorUnary(routeReason, trace, s"Service [$serviceName] does not have a command named: [$commandName]")
        }
      case None =>
        reportErrorUnary(routeReason, trace, s"Service [$serviceName] unknown")
    }

  private final def reportError(routeReason: RouteReason,
                                trace: List[(RouteReason, String, String)],
                                error: String): Exception = {
    val firstReason = if (routeReason == RouteReason.Initial) "" else s"\n  ${routeReason.trace}"

    val errorWithTrace = trace
      .map {
        case (RouteReason.Initial, service, command) => s"$service.$command"
        case (reason, service, command) => s"$service.$command\n  ${reason.trace} "
      }
      .mkString(error + firstReason, "", "")

    entityDiscovery.reportError(UserFunctionError(errorWithTrace))
    new Exception("Error")
  }

  private final def reportErrorFlow(routeReason: RouteReason,
                                    trace: List[(RouteReason, String, String)],
                                    error: String): Flow[Any, Nothing, NotUsed] =
    Flow.fromSinkAndSource(Sink.ignore, Source.failed(reportError(routeReason, trace, error)))

  private final def reportErrorUnary(routeReason: RouteReason,
                                     trace: List[(RouteReason, String, String)],
                                     error: String): Future[Nothing] =
    Future.failed(reportError(routeReason, trace, error))

}

object UserFunctionRouter {
  case class Message(payload: ScalaPbAny, metadata: Metadata)
}

private final case class EntityCommands(name: String, entitySupport: UserFunctionTypeSupport, commands: Set[String])

private sealed trait RouteReason {
  def trace: String
}
private object RouteReason {
  case object SideEffect extends RouteReason {
    override val trace = "side-effect of"
  }
  case object Forwarded extends RouteReason {
    override val trace = "forwarded from"
  }
  case object Initial extends RouteReason {
    override val trace = ""
  }
}
