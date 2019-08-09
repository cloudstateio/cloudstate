package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.cloudstate.entity.{ClientAction, EntityDiscovery, Forward, SideEffect, UserFunctionError}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class UserFunctionRouter(entities: Seq[ServableEntity], entityDiscovery: EntityDiscovery)(implicit mat: Materializer, ec: ExecutionContext) {

  private[this] final val entityCommands = entities.map {
    case ServableEntity(serviceName, serviceDescriptor, entitySupport) =>
      serviceName -> EntityCommands(serviceName, entitySupport,
        serviceDescriptor.getMethods.asScala.map(_.getName).toSet)
  }.toMap

  def handle(serviceName: String): Flow[UserFunctionCommand, UserFunctionReply, NotUsed] = {
    Flow[UserFunctionCommand].flatMapConcat { command =>
      routeMessage(Nil, RouteReason.Initial, serviceName, command.name, command.payload, synchronous = true)
    }
  }

  def handleUnary(serviceName: String, command: UserFunctionCommand): Future[UserFunctionReply] = {
    routeMessageUnary(Nil, RouteReason.Initial, serviceName, command.name, command.payload)
  }

  private def route(trace: List[(RouteReason, String, String)]): Flow[UserFunctionReply, UserFunctionReply, NotUsed] = {

    Flow[UserFunctionReply].flatMapConcat { response =>
      val sideEffects = Source(response.sideEffects.toList)
          .flatMapConcat {
            case SideEffect(serviceName, commandName, payload, synchronous) =>
              routeMessage(trace, RouteReason.SideEffect, serviceName, commandName, payload, synchronous)
          }

      val nextAction = response.clientAction match {
        case Some(ClientAction(ClientAction.Action.Forward(Forward(serviceName, commandName, payload)))) =>
          routeMessage(trace, RouteReason.Forwarded, serviceName, commandName, payload, synchronous = true)
        case None | Some(ClientAction(ClientAction.Action.Empty)) =>
          Source.empty
        case _ =>
          Source.single(response)
      }

      // First do the side effects, but ignore the response, then do the next action
      sideEffects.filter(_ => false)
        .concat(nextAction)
    }
  }

  private def routeUnary(trace: List[(RouteReason, String, String)], response: UserFunctionReply): Future[UserFunctionReply] = {
    val afterSideEffects = response.sideEffects.foldLeft(Future.successful[Any](())) { (future, sideEffect) =>
      future.flatMap { _ =>
        val sideEffectFuture = routeMessageUnary(trace, RouteReason.SideEffect, sideEffect.serviceName, sideEffect.commandName, sideEffect.payload)
        if (sideEffect.synchronous) {
          sideEffectFuture
        } else {
          Future.successful(())
        }
      }
    }

    afterSideEffects.flatMap { _ =>
      response.clientAction match {
        case Some(ClientAction(ClientAction.Action.Forward(Forward(serviceName, commandName, payload)))) =>
          routeMessageUnary(trace, RouteReason.Forwarded, serviceName, commandName, payload)
        case _ =>
          Future.successful(response)
      }
    }
  }

  private def routeMessage(trace: List[(RouteReason, String, String)], routeReason: RouteReason, serviceName: String, commandName: String,
    payload: Option[com.google.protobuf.any.Any], synchronous: Boolean): Source[UserFunctionReply, NotUsed] = {

    val source = entityCommands.get(serviceName) match {
      case Some(EntityCommands(_, entitySupport, commands)) =>
        if (commands(commandName)) {
          Source.single(UserFunctionCommand(commandName, payload))
            .via(entitySupport.handler(commandName))
            .via(route((routeReason, serviceName, commandName) :: trace))
        } else {
          reportErrorSource(routeReason, trace, s"Service [$serviceName] does not have a command named: [$commandName]")
        }
      case None =>
        reportErrorSource(routeReason, trace, s"Service [$serviceName] unknown")
    }

    if (synchronous) {
      // Return the source as is so that it gets executed as part of the main flow
      source
    } else {
      // This side effect is not synchronous, so we run it asynchronously and ignore the result, and return
      // nothing to do
      source.runWith(Sink.ignore)
      Source.empty
    }
  }

  private def routeMessageUnary(trace: List[(RouteReason, String, String)], routeReason: RouteReason, serviceName: String, commandName: String,
    payload: Option[com.google.protobuf.any.Any]): Future[UserFunctionReply] = {

    entityCommands.get(serviceName) match {
      case Some(EntityCommands(_, entitySupport, commands)) =>
        if (commands(commandName)) {
          entitySupport.handleUnary(UserFunctionCommand(commandName, payload)).flatMap { result =>
            routeUnary((routeReason, serviceName, commandName) :: trace, result)
          }
        } else {
          reportErrorUnary(routeReason, trace, s"Service [$serviceName] does not have a command named: [$commandName]")
        }
      case None =>
        reportErrorUnary(routeReason, trace, s"Service [$serviceName] unknown")
    }
  }

  private def reportError(routeReason: RouteReason, trace: List[(RouteReason, String, String)], error: String): Exception = {
    val firstReason = if (routeReason == RouteReason.Initial) "" else s"\n  ${routeReason.trace}"

    val errorWithTrace = trace.map {
      case (RouteReason.Initial, service, command) => s"$service.$command"
      case (reason, service, command) => s"$service.$command\n  ${reason.trace} "
    }.mkString(error + firstReason, "", "")

    entityDiscovery.reportError(UserFunctionError(errorWithTrace))
    new Exception("Error")
  }

  private def reportErrorSource(routeReason: RouteReason, trace: List[(RouteReason, String, String)], error: String): Source[Nothing, NotUsed] = {
    Source.failed(reportError(routeReason, trace, error))
  }

  private def reportErrorUnary(routeReason: RouteReason, trace: List[(RouteReason, String, String)], error: String): Future[Nothing] = {
    Future.failed(reportError(routeReason, trace, error))
  }

}

private case class EntityCommands(name: String, entitySupport: UserFunctionTypeSupport, commands: Set[String])

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

