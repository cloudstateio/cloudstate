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

package io.cloudstate.proxy.eventing

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.model.{MediaType, MediaTypes}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, MergeSequence, Partition, Source, Zip}
import akka.stream.{FlowShape, Materializer}
import com.google.protobuf.TextFormat.ParseException
import com.google.protobuf.{ByteString, CodedOutputStream, UnsafeByteOperations, WireFormat}
import com.typesafe.config.Config
import io.cloudstate.eventing.{EventDestination => EventDestinationProto, EventSource => EventSourceProto}
import io.cloudstate.protocol.entity.{ClientAction, Metadata, MetadataEntry}
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.eventing.{Eventing, EventingProto}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.Descriptors.MethodDescriptor
import io.cloudstate.proxy.UserFunctionRouter
import io.cloudstate.proxy.entity.UserFunctionReply
import io.cloudstate.proxy.protobuf.Options
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait Emitter {
  def emit(payload: ProtobufAny, method: MethodDescriptor, metadata: Option[Metadata]): Future[Done]
}

object Emitters {
  val ignore: Emitter = (payload: ProtobufAny, method: MethodDescriptor, metadata: Option[Metadata]) =>
    Future.successful(Done)

  def eventDestinationEmitter(eventDestination: EventDestination): Emitter =
    new EventDestinationEmitter(eventDestination)

  private class EventDestinationEmitter(eventDestination: EventDestination) extends Emitter {
    override def emit(payload: ProtobufAny, method: MethodDescriptor, metadata: Option[Metadata]): Future[Done] =
      eventDestination.emitSingle(
        EventingManager.createDesintationEvent(payload, method.getService.getFullName, metadata)
      )
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
            case Some(Eventing(_, Some(out), _)) =>
              map.get(out) match {
                case Some(methods) =>
                  map.updated(out, method :: methods)
                case None =>
                  map.updated(out, method :: Nil)
              }
            case _ => map
          }
      }

    outs.flatMap {
      case (dest @ EventDestinationProto(EventDestinationProto.Destination.Topic(topic), _), methods) =>
        val emitter = topicSupport match {
          case Some(support) => Emitters.eventDestinationEmitter(support.createDestination(dest))
          case None =>
            throw new IllegalArgumentException(
              s"Service call [${methods.head.getFullName}] declares an event destination topic of [$topic], but no topic support is configured."
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
                                         materializer: Materializer): Option[EventingSupport] =
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
      eventLogSupport: Option[EventingSupport],
      projectionSupport: Option[ProjectionSupport]
  )(implicit ec: ExecutionContext): Future[Cancellable] = {

    val consumers = createConsumers(entities)
    val preparedTables = if (consumers.exists(_.eventSource.source.isEventLog)) {
      projectionSupport.fold(Future.successful(Done.done()))(_.prepare())
    } else Future.successful(Done)

    preparedTables.map { _ =>
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
  }

  private def startConsumer(
      router: UserFunctionRouter,
      topicSupport: Option[EventingSupport],
      eventLogSupport: Option[EventingSupport]
  )(consumer: EventConsumer): Cancellable = {

    val maybeEventingSupport = consumer.eventSource match {
      case EventSourceProto(_, EventSourceProto.Source.Topic(_), _) =>
        topicSupport
      case EventSourceProto(_, EventSourceProto.Source.EventLog(_), _) =>
        eventLogSupport
      case EventSourceProto(_, EventSourceProto.Source.Empty, _) =>
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
      case dest @ EventDestinationProto(EventDestinationProto.Destination.Topic(topic), _) =>
        topicSupport match {
          case Some(support) => support.createDestination(dest)
          case None =>
            throw new IllegalArgumentException(
              s"Eventing consumer has declared an output topic [$topic}], but no topic eventing support has been provided."
            )
        }
      case EventDestinationProto(EventDestinationProto.Destination.Empty, _) =>
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
          killSwitch.cancel()
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
  private def entityToCommand[Ref](consumer: EventConsumer): Flow[SourceEvent[Ref], MessageIn[Ref], NotUsed] =
    Flow[SourceEvent[Ref]].map { sourceEvent =>
      val cloudEvent = sourceEvent.event

      val messageAny = MediaType.parse(cloudEvent.datacontenttype) match {
        case Right(protobuf) if protobuf.isApplication && ProtobufMediaSubTypes(protobuf.subType) =>
          val messageType = protobuf.params
            .get("proto")
            .orElse(protobuf.params.get("messageType"))
            .getOrElse(cloudEvent.`type`)
          ProtobufAny("type.googleapis.com/" + messageType, cloudEvent.data.getOrElse(ByteString.EMPTY))

        case Right(any) if any.isApplication && any.subType == ProtobufAnyMediaSubType =>
          // This is the content type that event logging will use
          ProtobufAny(cloudEvent.`type`, cloudEvent.data.getOrElse(ByteString.EMPTY))

        case Right(MediaTypes.`application/json`) =>
          encodeJsonToAny(cloudEvent.data, cloudEvent.`type`)

        case Right(typedJson) if typedJson.isApplication && typedJson.subType.endsWith("+json") =>
          encodeJsonToAny(cloudEvent.data, cloudEvent.`type`)

        case Right(utf8) if utf8.isText && utf8.params.get("charset").forall(_ == "utf-8") =>
          // Fast case for UTF-8 so we don't have to decode and reencode it
          encodeUtf8StringBytesToAny(cloudEvent.data)

        case Right(string) if string.isText =>
          encodeStringToAny(cloudEvent.data.getOrElse(ByteString.EMPTY).toString(string.params("charset")))

        case _ =>
          encodeBytesToAny(cloudEvent.data)
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

      MessageIn(sourceEvent.ref,
                consumerMethod,
                UserFunctionRouter.Message(messageAny, cloudEventToMetadata(cloudEvent)))
    }

  private def cloudEventToMetadata(cloudEvent: CloudEvent): Metadata = {
    import MetadataEntry.Value.StringValue
    // We use the HTTP binary mode transcoding rules
    val builder = Seq.newBuilder[MetadataEntry]
    builder += MetadataEntry("ce-id", StringValue(cloudEvent.id))
    builder += MetadataEntry("ce-source", StringValue(cloudEvent.source))
    builder += MetadataEntry("ce-specversion", StringValue(cloudEvent.specversion))
    builder += MetadataEntry("ce-type", StringValue(cloudEvent.`type`))
    builder += MetadataEntry("Content-Type", StringValue(cloudEvent.datacontenttype))

    cloudEvent.dataschema.foreach(v => builder += MetadataEntry("ce-dataschema", StringValue(v)))
    cloudEvent.subject.foreach(v => builder += MetadataEntry("ce-subject", StringValue(v)))
    cloudEvent.time.foreach(v => builder += MetadataEntry("ce-time", StringValue(v.toString)))

    Metadata(builder.result())
  }

  /**
   * This flow is responsible for routing commands through the router.
   */
  private def routeCommands[Ref](router: UserFunctionRouter): Flow[MessageIn[Ref], RouteResult[Ref], NotUsed] =
    Flow[MessageIn[Ref]].flatMapConcat {
      case MessageIn(eventSourceRef, consumerMethod, message) =>
        if (consumerMethod.methodDescriptor.isServerStreaming || consumerMethod.methodDescriptor.isClientStreaming) {
          Source
            .single(message)
            .via(
              router.handle(consumerMethod.methodDescriptor.getService.getFullName,
                            consumerMethod.methodDescriptor.getName,
                            Metadata.defaultInstance)
            )
            .map(reply => ResultPart(consumerMethod.outIndex, reply))
            .concat(Source.single(ResultEnd(eventSourceRef)))
        } else {
          Source
            .future(
              router.handleUnary(consumerMethod.methodDescriptor.getService.getFullName,
                                 consumerMethod.methodDescriptor.getName,
                                 message)
            )
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

          // A rough description of this flow:
          //
          // - The stream is a a series of ResultPart messages followed by ResultEnd messages. ResultEnd indicates that
          //   this is the result of processing a single message, and that message should now be acknowledged, there is
          //   a 1:1 in order relationship with these messages.
          // - Each result part has an optional output port number for it to be routed to. If present, the result gets
          //   routed through that output before going out to be acknowledged. If not present, it bypasses all outputs.
          //
          // In -> Partition based on out index              /-> MergeSequence -> collect result end refs for ack ->
          //       +-> Split to carry the ack ref /-> Merge -+
          //       |   +-> Output one ------------+          |
          //       |   \--------------------------/          |
          //       +-> Split to carry the ack ref /-> Merge--+
          //       |   +-> Output two ------------+          |
          //       |   \--------------------------+          |
          //       \-> Bypass all outputs -------------------/
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
                case (ResultPart(_, UserFunctionReply(Some(ClientAction(ClientAction.Action.Reply(reply), _)), _, _)),
                      _) =>
                  createDesintationEvent(reply.payload.get, consumer.entity.serviceName, reply.metadata)
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

  def createDesintationEvent(payload: ProtobufAny, serviceName: String, maybeMetadata: Option[Metadata]) = {
    val metadata = maybeMetadata
      .getOrElse(Metadata.defaultInstance)
      .entries
      .collect {
        case MetadataEntry(key, MetadataEntry.Value.StringValue(value), _) => key -> value
      }
      .toMap

    val (ceType, contentType, bytes) = payload.typeUrl match {
      case json if json.startsWith("json.cloudstate.io/") =>
        (json.stripPrefix("json.cloudstate.io/"), "application/json", decodeBytes(payload))
      case "p.cloudstate.io/string" =>
        ("", "text/plain; charset=utf-8", decodeBytes(payload))
      case "p.cloudstate.io/bytes" =>
        ("", "application/octet-stream", decodeBytes(payload))
      case generic =>
        (generic.dropWhile(_ != '/').drop(1), "application/protobuf", payload.value)
    }

    DestinationEvent(
      CloudEvent(
        id = metadata.getOrElse("ce-id", UUID.randomUUID().toString),
        source = metadata.getOrElse("ce-source", serviceName),
        specversion = metadata.getOrElse("ce-specversion", "1.0"),
        `type` = metadata.getOrElse("ce-type", ceType),
        datacontenttype = metadata.getOrElse("Content-Type", contentType),
        dataschema = metadata.get("ce-dataschema"),
        subject = metadata.get("ce-subject"),
        // todo the time can be any RFC3339 time string, Instant.parse only parses ISO8601, which is just one allowable
        // format in RFC3339
        time = metadata.get("ce-time").map(Instant.parse).orElse(Some(Instant.now())),
        data = Some(bytes)
      )
    )
  }

  private case class MessageIn[Ref](eventSourceRef: Ref,
                                    consumerMethod: EventConsumerMethod,
                                    message: UserFunctionRouter.Message)

  private sealed trait RouteResult[+Ref]

  private case class ResultPart(outIdx: Option[Int], out: UserFunctionReply) extends RouteResult[Nothing]

  private case class ResultEnd[Ref](sourceEventRef: Ref) extends RouteResult[Ref]

  private val ProtobufMediaSubTypes = Set("protobuf", "x-protobuf", "vnd.google.protobuf")
  val ProtobufAnyMediaSubType = "vnd.cloudstate.protobuf.any"

  private def encodeByteArray(maybeBytes: Option[ByteString]) = maybeBytes match {
    case None => ByteString.EMPTY
    case Some(bytes) if bytes.isEmpty =>
      // Create a byte array the right size. It needs to have the tag and enough space to hold the length of the data
      // (up to 5 bytes).
      // Length encoding consumes 1 byte for every 7 bits of the field
      val bytesLengthFieldSize = ((31 - Integer.numberOfLeadingZeros(bytes.size())) / 7) + 1
      val byteArray = new Array[Byte](1 + bytesLengthFieldSize)
      val stream = CodedOutputStream.newInstance(byteArray)
      stream.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED)
      stream.writeUInt32NoTag(bytes.size())
      UnsafeByteOperations.unsafeWrap(byteArray).concat(bytes)
  }

  private def encodeBytesToAny(bytes: Option[ByteString]): ProtobufAny =
    ProtobufAny("p.cloudstate.io/bytes", encodeByteArray(bytes))

  private def encodeJsonToAny(bytes: Option[ByteString], jsonType: String): ProtobufAny =
    ProtobufAny("json.cloudstate.io/" + jsonType, encodeByteArray(bytes))

  private def encodeUtf8StringBytesToAny(bytes: Option[ByteString]): ProtobufAny =
    ProtobufAny("p.cloudstate.io/string", encodeByteArray(bytes))

  private def encodeStringToAny(string: String): ProtobufAny = {
    val builder = ByteString.newOutput()
    val stream = CodedOutputStream.newInstance(builder)
    stream.writeString(1, string)
    ProtobufAny("p.cloudstate.io/string", builder.toByteString)
  }

  private def decodeBytes(payload: ProtobufAny): ByteString = {
    val stream = payload.value.newCodedInput()
    @annotation.tailrec
    def findField(): ByteString =
      stream.readTag() match {
        case 0 =>
          // 0 means EOF
          ByteString.EMPTY
        case feild1 if WireFormat.getTagFieldNumber(feild1) == 1 =>
          if (WireFormat.getTagWireType(feild1) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
            stream.readBytes()
          } else {
            throw new ParseException("Expected length delimited field, tag was: " + feild1)
          }
        case other =>
          stream.skipField(other)
          findField()
      }
    findField()
  }

}
