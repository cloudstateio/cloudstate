package io.cloudstate.proxy.eventing

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Merge, RunnableGraph, Sink, Source}
import akka.parboiled2.util.Base64

import io.cloudstate.protocol.entity.{ClientAction, EntityDiscoveryClient, Failure, Reply, UserFunctionError}
import io.cloudstate.proxy.{Serve, UserFunctionRouter}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.protobuf.{Options, Types}
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}

import io.cloudstate.eventing.{Eventing, EventingProto}

import com.google.protobuf.{ByteString => ProtobufByteString}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.{FileDescriptor, MethodDescriptor}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import com.typesafe.config.Config

import org.slf4j.{Logger, LoggerFactory}

import Serve.{CommandHandler}

trait EventingSupport {
  def createSource(sourceName: String, handler: CommandHandler): Source[ProtobufByteString, Future[Cancellable]]
  def createDestination(destinationName: String, handler: CommandHandler): Flow[ProtobufByteString, AnyRef, NotUsed]

  final def serve(eventing: Eventing,
                  commandHandler: CommandHandler,
                  entityDiscoveryClient: EntityDiscoveryClient,
                  log: Logger): Source[AnyRef, Future[Cancellable]] =
    createSource(eventing.in, commandHandler)
      .map(commandHandler.serializer.parse)
      .via(commandHandler.flowUsing(entityDiscoveryClient, log))
      .via(createDestination(eventing.out, commandHandler))
      .dropWhile(_ => true) // TODO decide what to do with these messages, we want to keep on forever if possible
}

class TestEventingSupport(config: Config, materializer: ActorMaterializer) extends EventingSupport {
  final val pollInitialDelay: FiniteDuration = config.getDuration("poll-initial-delay").toMillis.millis
  final val pollInterval: FiniteDuration = config.getDuration("poll-interval").toMillis.millis

  final val sampleData = config.getConfig("data")

  def createSource(sourceName: String, handler: CommandHandler): Source[ProtobufByteString, Future[Cancellable]] = {
    EventingManager.log.debug("Creating eventing source for {}", sourceName)
    val command =
      sampleData.getString(sourceName) match {
        case null | "" =>
          EventingManager.log.error("No sample data found for {}", handler.fullCommandName)
          throw new IllegalStateException(s"No test sample data found for ${handler.fullCommandName}")
        case data => ProtobufByteString.copyFrom(Base64.rfc2045.decode(data))
      }
    Source.tick(pollInitialDelay, pollInterval, command).mapMaterializedValue(_ => Future.never)
  }

  def createDestination(destinationName: String, handler: CommandHandler): Flow[ProtobufByteString, AnyRef, NotUsed] = {
    val msg = if (destinationName == "") "Discarding response: {}" else "Publishing response: {}"
    Flow[ProtobufByteString].alsoTo(Sink.foreach(m => EventingManager.log.info(msg, m)))
  }
}

object EventingManager {

  final val log = LoggerFactory.getLogger("EventingManager")

  final case class EventMapping private (entity: ServableEntity, routes: Map[MethodDescriptor, Eventing])

  // Contains all entities which has at least one endpoint which accepts events
  def createEventMappings(entities: Seq[ServableEntity]): Seq[EventMapping] =
    entities.flatMap { entity =>
      val endpoints =
        entity.serviceDescriptor.getMethods.iterator.asScala.foldLeft(Map.empty[MethodDescriptor, Eventing]) {
          case (map, method) =>
            val e = EventingProto.eventing.get(Options.convertMethodOptions(method))
            log.debug("EventingProto.events for {}", method.getFullName + " " + e)
            e.fold(map)(map.updated(method, _))
        }

      if (endpoints.isEmpty) Nil
      else List(EventMapping(entity, endpoints))
    }

  def createSupport(eventConfig: Config)(implicit materializer: ActorMaterializer): Option[EventingSupport] =
    eventConfig.getString("support") match {
      case s @ "google-pubsub" =>
        log.info("Creating google-pubsub eventing support")
        Some(new GCPubsubEventingSupport(eventConfig.getConfig(s), materializer))
      case s @ "test" =>
        log.info("Creating test eventing support")
        Some(new TestEventingSupport(eventConfig.getConfig(s), materializer))
      case "none" =>
        log.info("Eventing support turned off in configuration")
        None
      case other =>
        throw new IllegalStateException(s"Check your configuration. There is no eventing support named: $other")
    }

  def createStreams(router: UserFunctionRouter,
                    entityDiscoveryClient: EntityDiscoveryClient,
                    entities: Seq[ServableEntity],
                    support: EventingSupport): Option[RunnableGraph[Future[Done]]] =
    (for {
      EventMapping(entity, routes) <- createEventMappings(entities)
      (mdesc, eventing) <- routes
    } yield {
      log.debug("Creating route for {}", eventing)
      support.serve(eventing, new CommandHandler(entity, mdesc, router), entityDiscoveryClient, log)
    }) match {
      case Seq() => None
      case Seq(s) => Some(s.toMat(Sink.ignore)(Keep.right))
      case Seq(s1, s2, sN @ _*) => Some(Source.combine(s1, s2, sN: _*)(Merge(_)).toMat(Sink.ignore)(Keep.right))
    }
}
