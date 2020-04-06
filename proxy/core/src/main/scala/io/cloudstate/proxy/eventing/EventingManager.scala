package io.cloudstate.proxy.eventing

import java.util.concurrent.atomic.AtomicBoolean

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{HttpCharsets, MediaTypes}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Partition, Source, Zip}
import akka.stream.{ActorMaterializer, FlowShape, KillSwitch, Materializer}
import com.google.protobuf.Descriptors.MethodDescriptor
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.{ByteString, CodedOutputStream, UnsafeByteOperations}
import com.typesafe.config.Config
import io.cloudstate.eventing.{
  Eventing,
  EventingProto,
  EventDestination => EventDestinationProto,
  EventSource => EventSourceProto
}
import io.cloudstate.protocol.entity.ClientAction
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.UserFunctionRouter
import io.cloudstate.proxy.entity.{UserFunctionCommand, UserFunctionReply}
import io.cloudstate.proxy.protobuf.Options
import io.cloudstate.proxy.streams.MergeSequence
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Future

trait Emitter {
  def emit(payload: ProtobufAny, method: MethodDescriptor): Future[Done]
}

object Emitters {
  val ignore: Emitter = (payload: ProtobufAny, method: MethodDescriptor) => Future.successful(Done)

  def eventDestinationEmitter(eventDestination: EventDestination): Emitter =
    new EventDestinationEmitter(eventDestination)

  private class EventDestinationEmitter(eventDestination: EventDestination) extends Emitter {
    override def emit(payload: ProtobufAny, method: MethodDescriptor): Future[Done] =
      eventDestination.emitSingle(DestinationEvent(payload))
  }
}

object EventingManager {

  final val log = LoggerFactory.getLogger("EventingManager")

  final case class EventConsumer(eventSource: EventSourceProto,
                                 entity: ServableEntity,
                                 methods: Map[String, EventConsumerMethod],
                                 outs: Vector[EventDestinationProto])

  final case class EventConsumerMethod(eventing: Eventing, methodDescriptor: MethodDescriptor, outIndex: Option[Int])

  private def createConsumers(entities: List[ServableEntity]): List[EventConsumer] =
    entities.flatMap { entity =>
      val unindexedConsumers = entity.serviceDescriptor.getMethods.iterator.asScala
        .foldLeft(Map.empty[EventSourceProto, EventConsumer]) {
          case (map, method) =>
            EventingProto.eventing.get(Options.convertMethodOptions(method)) match {
              case None => map
              case Some(e) =>
                (e.in, e.out) match {
                  case (None, None) => map
                  case (Some(in), Some(out)) if in.source.topic.exists(out.destination.topic.contains) =>
                    throw new IllegalStateException(
                      s"Endpoint [${method.getFullName}] has the same input topic as output topic [${in.source.topic.getOrElse("")}], this is not allowed."
                    )
                  case (Some(in), _) =>
                    map.get(in) match {
                      case Some(consumer) =>
                        consumer.methods.get(method.getInputType.getFullName).foreach { conflict =>
                          throw new IllegalStateException(
                            s"Endpoints [${conflict.methodDescriptor.getFullName}] and [${method.getFullName}] both subscribe to the same event source with the same input type. If you wish to define two independent consumers of the same event source, use the consumer_group annotation on the source to identify them."
                          )
                        }
                        map.updated(in,
                                    consumer.copy(
                                      methods = consumer.methods.updated(method.getInputType.getFullName,
                                                                         EventConsumerMethod(e, method, None))
                                    ))
                      case None =>
                        map.updated(
                          in,
                          EventConsumer(in,
                                        entity,
                                        Map(method.getInputType.getFullName -> EventConsumerMethod(e, method, None)),
                                        Vector.empty)
                        )
                    }
                }
            }
        }
        .values

      unindexedConsumers.map { consumer =>
        val outs = consumer.methods.values
          .flatMap(_.eventing.out)
          .toVector
          .distinct

        val indexedOuts = outs.zipWithIndex.toMap

        val indexedMethods = consumer.methods.toList.map {
          case (inputType, method) => (inputType, method.copy(outIndex = method.eventing.out.flatMap(indexedOuts.get)))
        }.toMap

        consumer.copy(methods = indexedMethods, outs = outs)
      }
    }

  def createEmitters(
      entities: List[ServableEntity],
      topicSupport: Option[EventingSupport]
  )(implicit system: ActorSystem, mat: Materializer): Map[String, Emitter] = {
    val outs = entities
      .flatMap(_.serviceDescriptor.getMethods.iterator().asScala)
      .foldLeft(Map.empty[EventDestinationProto, List[MethodDescriptor]]) {
        case (map, method) =>
          EventingProto.eventing.get(Options.convertMethodOptions(method)) match {
            case None => map
            case Some(Eventing(_, Some(out))) =>
              map.get(out) match {
                case Some(methods) =>
                  map.updated(out, method :: methods)
                case None =>
                  map.updated(out, method :: Nil)
              }
          }
      }

    outs.flatMap {
      case (dest @ EventDestinationProto(EventDestinationProto.Destination.Topic(topic)), methods) =>
        val emitter = topicSupport match {
          case Some(support) => Emitters.eventDestinationEmitter(support.createDestination(dest))
          case None =>
            throw new IllegalArgumentException(
              s"Service call [${methods.head.getFullName}] declares an event destination topic of [$topic], but not topic support is configured."
            )
        }
        methods.map(method => method.getFullName -> emitter)
      case (_, methods) =>
        throw new IllegalArgumentException(
          s"Service call [${methods.head.getFullName}] has declared an event out with no topic."
        )
    }
  }

  def createSupport(eventConfig: Config)(implicit system: ActorSystem,
                                         materializer: ActorMaterializer): Option[EventingSupport] =
    eventConfig.getString("support") match {
      case "none" =>
        log.info("Eventing support turned off in configuration")
        None
      case s @ "google-pubsub" =>
        log.info("Creating google-pubsub eventing support")
        Some(new GCPubsubEventingSupport(eventConfig.getConfig(s)))
      case other =>
        throw new IllegalStateException(s"Check your configuration. There is no eventing support named: $other")
    }

  def startConsumers(
      router: UserFunctionRouter,
      entities: List[ServableEntity],
      topicSupport: Option[EventingSupport],
      eventLogSupport: Option[EventingSupport]
  ): Cancellable = {

    val consumers = createConsumers(entities)

    consumers match {
      case Nil => Cancellable.alreadyCancelled
      case consumers =>
        val running = consumers.map(startConsumer(router, topicSupport, eventLogSupport))

        new Cancellable {
          override def cancel(): Boolean =
            running.foldLeft(true)((success, cancellable) => success || cancellable.cancel())

          override def isCancelled: Boolean = running.forall(_.isCancelled)
        }
    }
  }

  private def startConsumer(
      router: UserFunctionRouter,
      topicSupport: Option[EventingSupport],
      eventLogSupport: Option[EventingSupport]
  )(consumer: EventConsumer): Cancellable = {

    val maybeEventingSupport = consumer.eventSource match {
      case EventSourceProto(_, EventSourceProto.Source.Topic(_)) =>
        topicSupport
      case EventSourceProto(_, EventSourceProto.Source.EventLog(_)) =>
        eventLogSupport
      case EventSourceProto(_, EventSourceProto.Source.Empty) =>
        throw new IllegalArgumentException(
          s"Eventing consumer [${consumer.methods.head._1}] has declared an input with no source."
        )
    }

    val eventSource = maybeEventingSupport match {
      case Some(eventingSupport) if eventingSupport.supportsSource =>
        eventingSupport.createSource(consumer.eventSource, consumer.entity.serviceName)
      case Some(eventingSupport) =>
        throw new IllegalArgumentException(
          s"Eventing consumer [${consumer.methods.head._1}] has declared an input of [${eventingSupport.name}], but this does not support being used as an event source."
        )
      case None =>
        throw new IllegalArgumentException(
          s"Eventing consumer [${consumer.methods.head._1}] has declared a [${consumer.eventSource.source}] event source, but this event source isn't supported."
        )
    }

    val eventDestinations = consumer.outs.map {
      case dest @ EventDestinationProto(EventDestinationProto.Destination.Topic(topic)) =>
        topicSupport match {
          case Some(support) => support.createDestination(dest)
          case None =>
            throw new IllegalArgumentException(
              s"Eventing consumer has declared an output topic [$topic}], but no topic eventing support has been provided."
            )
        }
      case EventDestinationProto(EventDestinationProto.Destination.Empty) =>
        throw new IllegalArgumentException(s"Eventing consumer has declared an input with no destination.")
    }

    val killSwitch = eventSource.run(
      entityToCommand[eventSource.SourceEventRef](consumer) via
      routeCommands(router) via
      forwardToOutputs(consumer, eventDestinations)
    )

    new Cancellable {
      private val cancelled = new AtomicBoolean()

      override def cancel(): Boolean =
        if (cancelled.compareAndSet(false, true)) {
          killSwitch.shutdown()
          true
        } else {
          false
        }

      override def isCancelled: Boolean = cancelled.get()
    }
  }

  /**
   * This flow is responsible for turning an entity that has come in from a event source into a command.
   *
   * The command is paired with information about which method it should be routed to, and where the result should be
   * output to.
   */
  private def entityToCommand[Ref](consumer: EventConsumer): Flow[SourceEvent[Ref], CommandIn[Ref], NotUsed] = {

    val defaultProtobufType = consumer.methods match {
      case single if single.size == 1 => Some(single.head._1)
      case multiple if multiple.contains(ProtobufAny.scalaDescriptor.fullName) =>
        Some(ProtobufAny.scalaDescriptor.fullName)
      case _ => None
    }

    Flow[SourceEvent[Ref]].map { sourceEvent =>
      val entity = sourceEvent.entity

      val messageAny = entity.contentType.mediaType match {
        case protobuf if protobuf.isApplication && ProtobufMediaSubTypes(protobuf.subType) =>
          val messageType = protobuf.params
            .get("proto")
            .orElse(protobuf.params.get("messageType"))
            .orElse(defaultProtobufType) match {
            case Some(mt) => mt
            case None =>
              throw new IllegalArgumentException(
                s"Incoming protobuf message on consumer ${consumer.eventSource} does not declare a protobuf type, and there is more than one method on the consuming entity to handle messages for it, and none of them accept the default type of google.protobuf.Any."
              )
          }
          ProtobufAny("type.googleapis.com/" + messageType, UnsafeByteOperations.unsafeWrap(entity.data.toByteBuffer))

        case any if any.isApplication && any.subType == ProtobufAnyMediaSubType =>
          // This is the content type that event logging will use
          any.params.get("typeUrl") match {
            case Some(typeUrl) =>
              ProtobufAny(typeUrl, UnsafeByteOperations.unsafeWrap(entity.data.toByteBuffer))
            case None =>
              throw new IllegalStateException(
                s"Message content type [${entity.contentType} does not contain a type url parameter."
              )
          }

        case MediaTypes.`application/json` =>
          // Todo: the type could come from CloudEvents
          encodeJsonToAny(entity.data, "object")

        case typedJson if typedJson.isApplication && typedJson.subType.endsWith("+json") =>
          encodeJsonToAny(entity.data, typedJson.subType.stripSuffix("+json"))

        case utf8 if utf8.isText && entity.contentType.charsetOption.forall(_ == HttpCharsets.`UTF-8`) =>
          // Fast case for UTF-8 so we don't have to decode and reencode it
          encodeUtf8StringBytesToAny(entity.data)

        case string if string.isText =>
          encodeStringToAny(entity.data.decodeString(entity.contentType.charsetOption.get.value))

        case _ =>
          encodeBytesToAny(entity.data)
      }

      // Select a method
      val maybeConsumerMethod =
        if (messageAny.typeUrl.startsWith("p.cloudstate.io/") || messageAny.typeUrl.startsWith("json.cloudstate.io/")) {
          consumer.methods.get(ProtobufAny.scalaDescriptor.fullName)
        } else {
          val desiredType = messageAny.typeUrl.split("/", 2).last
          consumer.methods.get(desiredType).orElse(consumer.methods.get(ProtobufAny.scalaDescriptor.fullName))
        }

      val consumerMethod = maybeConsumerMethod match {
        case Some(method) =>
          method
        case None =>
          throw new IllegalArgumentException(
            s"No method can be found to handle protobuf type of [${messageAny.typeUrl}] on input ${consumer.eventSource}. Either declare a method for this type, or declare a method that accepts google.protobuf.Any."
          )
      }

      val command = UserFunctionCommand(
        name = consumerMethod.methodDescriptor.getName,
        payload = Some(messageAny)
      )

      CommandIn(sourceEvent.ref, consumerMethod, command)
    }
  }

  /**
   * This flow is responsible for routing commands through the router.
   */
  private def routeCommands[Ref](router: UserFunctionRouter): Flow[CommandIn[Ref], RouteResult[Ref], NotUsed] =
    Flow[CommandIn[Ref]].flatMapConcat {
      case CommandIn(eventSourceRef, consumerMethod, command) =>
        if (consumerMethod.methodDescriptor.isServerStreaming || consumerMethod.methodDescriptor.isClientStreaming) {
          Source
            .single(command)
            .via(router.handle(consumerMethod.methodDescriptor.getService.getFullName))
            .map(reply => ResultPart(consumerMethod.outIndex, reply))
            .concat(Source.single(ResultEnd(eventSourceRef)))
        } else {
          Source
            .fromFuture(router.handleUnary(consumerMethod.methodDescriptor.getService.getFullName, command))
            .mapConcat { reply =>
              List(ResultPart(consumerMethod.outIndex, reply), ResultEnd(eventSourceRef))
            }
        }

    }

  /**
   * This flow is responsible for forwarding routing result replies on to the configuration destinations.
   */
  private def forwardToOutputs[Ref](consumer: EventConsumer,
                                    destinations: Vector[EventDestination]): Flow[RouteResult[Ref], Ref, NotUsed] =
    if (consumer.outs.isEmpty) {
      Flow[RouteResult[Ref]].collect {
        case ResultEnd(ref) => ref
      }
    } else {
      Flow[RouteResult[Ref]].zipWithIndex
        .via(Flow.fromGraph(GraphDSL.create() { implicit b =>
          import GraphDSL.Implicits._

          val bypassPort = consumer.outs.size
          val ports = bypassPort + 1

          val broadcast = b.add(
            Partition[(RouteResult[Ref], Long)](ports,
                                                result =>
                                                  result._1 match {
                                                    case ResultPart(outIdx, _) => outIdx.getOrElse(bypassPort)
                                                    case _ => bypassPort
                                                  })
          )

          val merge = b.add(new MergeSequence[(RouteResult[Ref], Long)](ports)(_._2))

          destinations.zipWithIndex.foreach {
            case (dest, outPort) =>
              val split = b.add(Broadcast[(RouteResult[Ref], Long)](2))
              val zip = b.add(Zip[AnyRef, (RouteResult[Ref], Long)]())

              broadcast.out(outPort) ~> split.in

              split.out(0).collect {
                case (ResultPart(_, UserFunctionReply(Some(ClientAction(ClientAction.Action.Reply(reply))), _)), _) =>
                  DestinationEvent(reply.payload.get)
                case (ResultPart(_, other), _) =>
                  throw new IllegalStateException(s"Reply from router did not have a reply client action: $other")
                // Shouldn't happen:
                case t => throw new IllegalStateException(s"result end routed through output flow? $t")
              } ~> dest.eventStreamOut ~> zip.in0
              split.out(1) ~> zip.in1

              zip.out.map(_._2) ~> merge.in(outPort)
          }

          broadcast.out(bypassPort) ~> merge.in(bypassPort)
          FlowShape(broadcast.in, merge.out)
        }))
        .collect {
          case (ResultEnd(ref), _) => ref
        }
    }

  private case class CommandIn[Ref](eventSourceRef: Ref,
                                    consumerMethod: EventConsumerMethod,
                                    command: UserFunctionCommand)

  private sealed trait RouteResult[+Ref]

  private case class ResultPart(outIdx: Option[Int], out: UserFunctionReply) extends RouteResult[Nothing]

  private case class ResultEnd[Ref](sourceEventRef: Ref) extends RouteResult[Ref]

  private val ProtobufMediaSubTypes = Set("protobuf", "x-protobuf", "vnd.google.protobuf")
  val ProtobufAnyMediaSubType = "vnd.cloudstate.protobuf.any"

  private def encodeByteArray(bytes: akka.util.ByteString) =
    if (bytes.isEmpty) {
      ByteString.EMPTY
    } else {
      // Create a byte array the right size. It needs to have the tag, enough space to hold the length of the data
      // (up to 5 bytes), and the data itself.
      // Length encoding consumes 1 byte for every 7 bits of the field
      val bytesLengthFieldSize = ((31 - Integer.numberOfLeadingZeros(bytes.length)) / 7) + 1
      val byteArray = new Array[Byte](1 + bytesLengthFieldSize + bytes.length)
      val stream = CodedOutputStream.newInstance(byteArray)
      stream.writeBytes(1, UnsafeByteOperations.unsafeWrap(bytes.toByteBuffer))
      UnsafeByteOperations.unsafeWrap(byteArray)
    }

  private def encodeBytesToAny(bytes: akka.util.ByteString): ProtobufAny =
    ProtobufAny("p.cloudstate.io/bytes", encodeByteArray(bytes))

  private def encodeJsonToAny(bytes: akka.util.ByteString, jsonType: String): ProtobufAny =
    ProtobufAny("json.cloudstate.io/" + jsonType, encodeByteArray(bytes))

  private def encodeUtf8StringBytesToAny(bytes: akka.util.ByteString): ProtobufAny =
    ProtobufAny("p.cloudstate.io/string", encodeByteArray(bytes))

  private def encodeStringToAny(string: String): ProtobufAny = {
    val builder = ByteString.newOutput()
    val stream = CodedOutputStream.newInstance(builder)
    stream.writeString(1, string)
    ProtobufAny("p.cloudstate.io/string", builder.toByteString)
  }

}
