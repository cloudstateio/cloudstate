package io.cloudstate.proxy.eventing

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri.Path
import akka.stream.{ActorMaterializer, FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Merge, Partition, RunnableGraph, Sink, Source}
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

trait Emitter {
  def emit(payload: ProtobufAny, method: MethodDescriptor): Boolean = ???
}

object Emitters {
  val ignore: Emitter = new Emitter {
    override def emit(payload: ProtobufAny, method: MethodDescriptor): Boolean = false
  }
}

trait EventingSupport {
  def createSource(sourceName: String, handler: CommandHandler): Source[ProtobufAny, Future[Cancellable]]
  def createDestination(destinationName: String, handler: CommandHandler): Flow[ProtobufAny, AnyRef, NotUsed]
}

class TestEventingSupport(config: Config, materializer: ActorMaterializer) extends EventingSupport {
  final val pollInitialDelay: FiniteDuration = config.getDuration("poll-initial-delay").toMillis.millis
  final val pollInterval: FiniteDuration = config.getDuration("poll-interval").toMillis.millis

  final val sampleData = config.getConfig("data")

  def createSource(sourceName: String, handler: CommandHandler): Source[ProtobufAny, Future[Cancellable]] = {
    EventingManager.log.debug("Creating eventing source for {}", sourceName)
    val command =
      sampleData.getString(sourceName) match {
        case null | "" =>
          EventingManager.log.error("No sample data found for {}", handler.fullCommandName)
          throw new IllegalStateException(s"No test sample data found for ${handler.fullCommandName}")
        case data => ProtobufAny.parseFrom(Base64.rfc2045.decode(data))
      }
    Source.tick(pollInitialDelay, pollInterval, command).mapMaterializedValue(_ => Future.never)
  }

  def createDestination(destinationName: String, handler: CommandHandler): Flow[ProtobufAny, AnyRef, NotUsed] = {
    val (msg, eventMsg) =
      if (destinationName == "")
        ("Discarding response: {}", "Discarding event: {}")
      else
        ("Publishing response: {}", "Publishing event: {}")

    Flow[ProtobufAny]
      .alsoTo(Sink.foreach(m => EventingManager.log.info(msg, m)))
  }
}

object EventingManager {

  final val log = LoggerFactory.getLogger("EventingManager")

  final case class EventMapping private (entity: ServableEntity, routes: Map[MethodDescriptor, Eventing])

  private val noEmitter = Future.successful(Emitters.ignore)

  // Contains all entities which has at least one endpoint which accepts events
  def createEventMappings(entities: Seq[ServableEntity]): Seq[EventMapping] =
    entities.flatMap { entity =>
      val endpoints =
        entity.serviceDescriptor.getMethods.iterator.asScala.foldLeft(Map.empty[MethodDescriptor, Eventing]) {
          case (map, method) =>
            val e = EventingProto.eventing.get(Options.convertMethodOptions(method))
            // FIXME Validate that in and out are set appropriately!!
            log.debug("EventingProto.events for {}", method.getFullName + " " + e)
            e.filter(e => e.in != "" || e.out != "").fold(map)(map.updated(method, _))
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
                    support: EventingSupport): Option[RunnableGraph[(Emitter, Future[Done])]] =
    createEventMappings(entities) match {
      case Nil => None
      case eventMappings =>
        val allEligibleOutputsByMethodDescriptor =
          eventMappings
            .flatMap(_.routes.collect {
              case (m, e) if e.out != "" => (m.getFullName, e.out)
            })
            .toMap

        type TopicName = String
        type Record = (TopicName, ProtobufAny)

        val inNOutBurger: Seq[
          (Option[(TopicName, Source[Record, Future[Cancellable]])], Option[(TopicName, Flow[Record, AnyRef, NotUsed])])
        ] =
          for {
            EventMapping(entity, routes) <- eventMappings
            (mdesc, eventing) <- routes
          } yield {
            log.info("Creating route for {}", eventing)
            val commandHandler = new CommandHandler(entity, mdesc, router) // Could we reuse these from Serve?

            val in = Option(eventing.in).collect({
              case topic if topic != "" =>
                val source =
                  support
                    .createSource(topic, commandHandler)
                    .map(commandHandler.serializer.parse)
                    .via(commandHandler.flowUsing(entityDiscoveryClient, log, noEmitter))
                    .collect({ case bytes if eventing.out != "" => (eventing.out, bytes) }) //Without an out there is nothing to persist
                (topic, source)
            })

            val out = Option(eventing.out).collect({
              case topic if topic != "" =>
                val dest = Flow[Record]
                  .map(_._2)
                  .via(support.createDestination(topic, commandHandler))
                  .dropWhile(_ => true)
                (topic, dest)
            })

            (in, out)
          }

        val sources = inNOutBurger.collect({ case (Some(in), _) => in })

        val deadLetters =
          Flow[Record].map(_._2).dropWhile(_ => true) // TODO Log at warn?

        val destinations =
          ("", deadLetters) +: inNOutBurger.collect({ case (_, Some(out)) => out })

        val emitter =
          Source.queue[Record](destinations.size * 128, OverflowStrategy.backpressure)

        val destinationMap = destinations.map(_._1).sorted.zipWithIndex.toMap
        val destinationSelector =
          (r: Record) => destinationMap.get(r._1).getOrElse(destinationMap("")) // Send to deadLetters if no match

        val eventingFlow = Flow
          .fromGraph(GraphDSL.create() { implicit b =>
            import GraphDSL.Implicits._

            val mergeForPublish = b.add(Merge[Record](sources.size + 1)) // 1 + for emitter

            val routeToDestination =
              b.add(Partition[Record](destinations.size, destinationSelector))

            val mergeForExit = b.add(Merge[AnyRef](destinations.size))

            sources.zipWithIndex foreach {
              case ((topicName, source), idx) =>
                b.add(source).out ~> mergeForPublish.in(idx + 1) // 0 we keep for emitter
            }

            mergeForPublish.out ~> routeToDestination.in

            destinations foreach {
              case (topicName, flow) =>
                routeToDestination.out(destinationMap(topicName)) ~> b.add(flow) ~> mergeForExit
            }

            FlowShape(mergeForPublish.in(0), mergeForExit.out)
          })

        Some(
          emitter
            .via(eventingFlow)
            .toMat(Sink.ignore)((queue, future) => {
              val emitter = new Emitter {
                override def emit(event: ProtobufAny, method: MethodDescriptor): Boolean =
                  if (event.value.isEmpty) false // TODO do we need to check this here?
                  else {
                    // FIXME Check expected type of event compared to method.getOutputType
                    allEligibleOutputsByMethodDescriptor
                      .get(method.getFullName)
                      .map(
                        out => queue.offer((out, event)) // FIXME handle this Future
                      )
                      .isDefined
                  }
              }
              (emitter, future)
            })
        )
    }
}
