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
import java.time.format.DateTimeFormatter

import com.typesafe.config.{Config, ConfigFactory}
import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.grpc.GrpcClientSettings
import akka.stream.{KillSwitch, KillSwitches, Materializer, OverflowStrategy, SourceShape}
import akka.stream.scaladsl.{Broadcast, Concat, Flow, GraphDSL, Keep, RestartSource, Sink, Source}
import io.cloudstate.eventing.{EventDestination => EventDestinationProto, EventSource => EventSourceProto}
import io.grpc.{
  CallCredentials => gRPCCallCredentials,
  Status => gRPCStatus,
  StatusRuntimeException => gRPCStatusRuntimeException
}
import io.grpc.auth.MoreCallCredentials
import com.google.auth.oauth2.GoogleCredentials
import com.google.pubsub.v1.pubsub.{
  PublishRequest,
  PubsubMessage,
  ReceivedMessage,
  StreamingPullRequest,
  StreamingPullResponse,
  Subscription,
  Topic,
  PublisherClient => ScalaPublisherClient,
  SubscriberClient => ScalaSubscriberClient
}
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

import com.google.protobuf.ByteString
import io.cloudstate.eventing

import scala.collection.immutable
import scala.util.{Success, Try}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/**
 * Connection settings used to establish Pub/Sub connection.
 */
final class PubSubSettings private (
    val host: String,
    val port: Int,
    val rootCa: Option[String] = None,
    val callCredentials: Option[gRPCCallCredentials] = None
) {

  def this(config: Config) = {
    this(host = config.getString("host"), port = config.getInt("port"), rootCa = config.getString("rootCa") match {
      case "none" => None
      case fileName => Some(fileName)
    }, callCredentials = config.getString("callCredentials") match {
      case "google-application-default" =>
        Some(
          MoreCallCredentials.from(
            GoogleCredentials.getApplicationDefault.createScoped(
              Collections.singletonList("https://www.googleapis.com/auth/pubsub")
            )
          )
        )
      case _ => None
    })
  }

  /**
   * Endpoint hostname where the gRPC connection is made.
   */
  def withHost(host: String): PubSubSettings = copy(host = host)

  /**
   * Endpoint port where the gRPC connection is made.
   */
  def withPort(port: Int): PubSubSettings = copy(port = port)

  /**
   * A filename on the classpath which contains the root certificate authority
   * that is going to be used to verify certificate presented by the gRPC endpoint.
   */
  def withRootCa(rootCa: String): PubSubSettings = copy(rootCa = Some(rootCa))

  /**
   * Credentials that are going to be used for gRPC call authorization.
   */
  def withCallCredentials(callCredentials: gRPCCallCredentials): PubSubSettings =
    copy(callCredentials = Some(callCredentials))

  private[this] final def copy(host: String = host,
                               port: Int = port,
                               rootCa: Option[String] = rootCa,
                               callCredentials: Option[gRPCCallCredentials] = callCredentials) =
    new PubSubSettings(host, port, rootCa, callCredentials)

  /**
   * Creates a GrpcClientSettings from this PubSubSettings
   */
  def createClientSettings()(implicit sys: ActorSystem): GrpcClientSettings = {
    val sslConfig = rootCa.fold("") { rootCa =>
      s"""
         |ssl-config {
         |  disabledKeyAlgorithms = []
         |  trustManager = {
         |    stores = [
         |      { type = "PEM", path = "$rootCa", classpath = true }
         |    ]
         |  }
         |}""".stripMargin
    }

    val akkaGrpcConfig =
      s"""
         |host = "$host"
         |port = $port
         |
         |$sslConfig
         |""".stripMargin

    val settings = //TODO consider using Discovery and/or other settings from: https://github.com/akka/akka-grpc/blob/master/runtime/src/main/resources/reference.conf#L36
      GrpcClientSettings.fromConfig(
        ConfigFactory
          .parseString(akkaGrpcConfig)
          .withFallback(sys.settings.config.getConfig("akka.grpc.client.\"*\""))
      )

    callCredentials
      .fold(settings)(settings.withCallCredentials)
      .withTls(rootCa.isDefined)
  }
}

object GCPubsubEventingSupport {
  final val MANUALLY = "manually"
  final val BY_PROXY = "by-proxy"
  final val USING_CRD = "using-crd"
}

class GCPubsubEventingSupport(config: Config)(implicit materializer: Materializer, system: ActorSystem)
    extends EventingSupport {

  import GCPubsubEventingSupport._
  import system.dispatcher

  final val projectId: String = config.getString("project-id")
  final val pollInterval: FiniteDuration = config.getDuration("poll-interval").toMillis.millis
  final val upstreamAckDeadline: FiniteDuration =
    config.getDuration("upstream-ack-deadline").toMillis.millis
  final val downstreamBatchDeadline: FiniteDuration =
    config.getDuration("downstream-batch-deadline").toMillis.millis
  final val downstreamBatchSize: Int = config.getInt("downstream-batch-size")

  private[this] final val upstreamAckDeadlineSeconds = upstreamAckDeadline.toSeconds.toInt

  final val manageTopicsAndSubscriptions = config.getString("manage-topics-and-subscriptions") match {
    case mode @ (MANUALLY | USING_CRD | BY_PROXY) => mode
    case other =>
      require(
        false,
        s"manage-topics-and-subscriptions must be one of: ${MANUALLY}, ${USING_CRD}, or ${BY_PROXY} but is '$other'"
      )
  }

  private[this] final val settings = new PubSubSettings(config)

  private def validate(): Unit = {
    require(!projectId.isEmpty, s"project-id cannot be empty")
    require(upstreamAckDeadline >= 10.seconds,
            s"upstream-ack-deadline must be at least 10 seconds but was ${upstreamAckDeadline}")
    require(upstreamAckDeadline <= 600.seconds,
            s"upstream-ack-deadline must be at most 600 seconds but was ${upstreamAckDeadline}")
    require(downstreamBatchSize > 0, s"downstream-batch-size must be at least 1 but was ${downstreamBatchSize}")
  }

  validate()

  // Create the gRPC clients used to communicate with Google Pubsub
  final val (subscriberClient, publisherClient) = {
    val clientSettings = settings.createClientSettings()

    // We're reusing the same clients for all communication
    val subscriberClient = ScalaSubscriberClient(clientSettings)
    val publisherClient = ScalaPublisherClient(clientSettings)

    // Make sure that we don't leak connections
    system.registerOnTermination(subscriberClient.close())
    system.registerOnTermination(publisherClient.close())

    (subscriberClient, publisherClient)
  }

  private[this] val batchResults =
    if (downstreamBatchDeadline > 0.seconds || downstreamBatchSize > 1) {
      Flow[DestinationEvent]
        .map(transformDestinationEvent)
        .groupedWithin(downstreamBatchSize, downstreamBatchDeadline)
    } else Flow[DestinationEvent].map(event => transformDestinationEvent(event) :: Nil)

  // This awkward code is to make up for the lack of Flow support in akka-grpc. Related issues:
  // https://github.com/akka/akka/issues/26592
  // https://github.com/akka/akka-grpc/issues/550
  private def sourceToSourceToFlow[In, Out, MOut](f: Source[In, NotUsed] => Source[Out, MOut]): Flow[In, Out, NotUsed] =
    Flow[In].prefixAndTail(0).flatMapConcat { case (Nil, in) => f(in) }

  private[this] final def runManualFlow(
      subscription: String,
      processingFlow: Flow[SourceEvent[String], String, _]
  ): KillSwitch = {

    val request =
      StreamingPullRequest(subscription = subscription, streamAckDeadlineSeconds = upstreamAckDeadlineSeconds)

    val streamingPull: Flow[StreamingPullRequest, StreamingPullResponse, NotUsed] =
      sourceToSourceToFlow(subscriberClient.streamingPull)

    val source = RestartSource.withBackoff(3.seconds, 30.seconds, 0.2) { () =>
      Source.fromGraph(GraphDSL.create[SourceShape[Nothing]]() { implicit builder =>
        import GraphDSL.Implicits._

        val concat = builder.add(Concat[StreamingPullRequest](2))
        val outSplitter = builder.add(Broadcast[StreamingPullResponse](2, eagerCancel = true))

        val responseToEvents = Flow[StreamingPullResponse]
          .mapConcat(_.receivedMessages.toVector) // Note: receivedMessages is most likely a Vector already due to impl, so should be a noop
          .map(transformReceivedMessage(subscription))

        val acksToRequest = Flow[String]
          .groupedWithin(10, upstreamAckDeadline / 2)
          .map(ackIds => StreamingPullRequest(ackIds = ackIds))

        val circularDeadlockBreaker = Flow[StreamingPullRequest].buffer(1, OverflowStrategy.backpressure)

        // This is the main line through the graph, where we send our pull request for the subscription down the
        // streaming pull gRPC request, convert to events, process, convert acks to requests (which acknowledge them),
        // then send those back to the streaming pull via the concat
        // In the middle we have the outSplitter, it's positioned after the streamingPull, so that when it cancels,
        // it immediately cancels the pull, but allows the events in progress to continue to be processed.
        Source.single(request) ~>
        concat ~>
        streamingPull ~>
        outSplitter ~>
        responseToEvents ~>
        processingFlow ~>
        acksToRequest ~>
        circularDeadlockBreaker ~>
        concat

        // Meanwhile, we peel off the output using outSplitter, and ignore everything in it.
        // This allows us to return a Source that when a failure occurs anywhere in the stream, will emit a failure,
        // so the RestartSource can handle it and restart. It also allows us to cancel the stream via that source when
        // we shutdown.
        val out = outSplitter.collect(PartialFunction.empty)

        SourceShape(out.outlet)
      })
    }

    source
      .viaMat(KillSwitches.single)(Keep.right)
      .to(Sink.ignore)
      .run()
  }

  private[this] final def runByProxyManagedFlow(
      sourceName: String,
      subscription: String,
      processingFlow: Flow[SourceEvent[String], String, _]
  ): Future[KillSwitch] = {
    val topic = s"projects/${projectId}/topics/${sourceName}"
    val t = Topic(topic)
    val s = Subscription(
      name = subscription,
      topic = topic,
      pushConfig = None,
      ackDeadlineSeconds = upstreamAckDeadlineSeconds,
      retainAckedMessages = false,
      messageRetentionDuration = None // TODO configure this?
    )

    for {
      _ <- publisherClient
        .createTopic(t)
        .recover({
          case ex: gRPCStatusRuntimeException if ex.getStatus.getCode == gRPCStatus.Code.ALREADY_EXISTS =>
            t
        })
      _ <- subscriberClient
        .createSubscription(s)
        .recover({
          case ex: gRPCStatusRuntimeException if ex.getStatus.getCode == gRPCStatus.Code.ALREADY_EXISTS =>
            s
        })
    } yield runManualFlow(subscription, processingFlow)
  }

  private[this] final def createUsingCrdManagedFlow(
      sourceName: String,
      subscription: String,
      processingFlow: Flow[SourceEvent[String], String, _]
  ): Future[KillSwitch] =
    throw new IllegalStateException("NOT IMPLEMENTED YET") // FIXME IMPLEMENT THIS: create CRD-requests

  override def name: String = "Google PubSub"

  override def supportsSource: Boolean = true

  override def createSource(source: EventSourceProto, serviceName: String): EventSource = new EventSource {
    override type SourceEventRef = String

    override def run(flow: Flow[SourceEvent[String], String, _]): Cancellable = {
      val consumerGroup = source.consumerGroup match {
        case "" => serviceName
        case cg => cg
      }
      val subscription = source.source match {
        case EventSourceProto.Source.Topic(topic) =>
          s"projects/$projectId/subscriptions/${topic}_$consumerGroup"
        case other =>
          throw new IllegalArgumentException(s"Google PubSub source unable to be used to server $other")

      }
      val killSwitch = manageTopicsAndSubscriptions match {
        case MANUALLY => runManualFlow(subscription, flow)
        case USING_CRD => futureKillSwitch(createUsingCrdManagedFlow(consumerGroup, subscription, flow))
        case BY_PROXY => futureKillSwitch(runByProxyManagedFlow(consumerGroup, subscription, flow))
      }

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
  }

  private def transformReceivedMessage(source: String)(receivedMessage: ReceivedMessage): SourceEvent[String] = {
    val message =
      receivedMessage.message.getOrElse(throw new IllegalArgumentException("Received message has no message"))
    // Using the spec here, which was not merged, to handle cloudevents:
    // https://github.com/google/knative-gcp/pull/1
    // case sensitivity?
    val maybeContentType = message.attributes.get("Content-Type").orElse(message.attributes.get("content-type"))
    val cloudEvent = maybeContentType match {
      case Some("application/cloudevents+json") =>
        // Todo: handle structured cloudevents
        throw new UnsupportedOperationException("CloudEvents structured binding not yet supported")

      case defaultCt if message.attributes.contains("ce-specversion") =>
        CloudEvent(
          id = message.attributes.getOrElse("ce-id", message.messageId),
          source = message.attributes.getOrElse("ce-source", source),
          specversion = message.attributes("ce-specversion"),
          `type` = message.attributes.getOrElse("ce-type", ""),
          datacontenttype =
            message.attributes.get("ce-datacontenttype").orElse(defaultCt).getOrElse("application/octet-stream"),
          dataschema = message.attributes.get("ce-dataschema"),
          subject = message.attributes.get("ce-subject"),
          time = message.attributes
            .get("ce-time")
            .flatMap(t => Try(Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(t))).toOption),
          data = Some(message.data)
        )

      case _ =>
        CloudEvent(
          id = message.messageId,
          source = source,
          specversion = "1.0",
          `type` = "",
          datacontenttype = maybeContentType.getOrElse("application/octet-stream"),
          dataschema = None,
          subject = None,
          time = message.publishTime.map(t => Instant.ofEpochSecond(t.seconds, t.nanos)),
          data = Some(message.data)
        )
    }

    SourceEvent(cloudEvent, receivedMessage.ackId)
  }

  override def supportsDestination: Boolean = true

  override def createDestination(destination: eventing.EventDestination): EventDestination = new EventDestination {

    private val topic = destination.destination match {
      case EventDestinationProto.Destination.Topic(topic) =>
        s"projects/$projectId/topic/$topic"
      case other =>
        throw new IllegalArgumentException(s"Google PubSub source unable to be used to server $other")
    }

    private val topicReady: Future[Done] = manageTopicsAndSubscriptions match {
      case MANUALLY => Future.successful(Done)
      case USING_CRD => createUsingCrdManagedDestination(topic = topic)
      case BY_PROXY => createByProxyManagedDestination(topic = topic)
    }

    private val destinationFlow: Flow[DestinationEvent, AnyRef, NotUsed] =
      batchResults
        .mapAsyncUnordered(1 /*parallelism*/ )(
          batch => publisherClient.publish(PublishRequest(topic = topic, messages = batch))
        )
        .mapConcat(_.messageIds)

    override def eventStreamOut: Flow[DestinationEvent, AnyRef, NotUsed] =
      topicReady.value match {
        case Some(Success(_)) =>
          destinationFlow
        case _ =>
          Flow
            .lazyInitAsync(() => topicReady.map(_ => destinationFlow))
            .mapMaterializedValue(_ => NotUsed)
      }

    override def emitSingle(destinationEvent: DestinationEvent): Future[Done] =
      topicReady.value match {
        case Some(Success(_)) =>
          publisherClient
            .publish(PublishRequest(topic, Seq(transformDestinationEvent(destinationEvent))))
            .map(_ => Done)
        case _ =>
          for {
            _ <- topicReady
            _ <- publisherClient.publish(PublishRequest(topic, Seq(transformDestinationEvent(destinationEvent))))
          } yield Done
      }
  }

  private[this] final def createByProxyManagedDestination(topic: String): Future[Done] =
    publisherClient
      .createTopic(Topic(topic))
      .map(_ => Done)
      .recover({
        case ex: gRPCStatusRuntimeException if ex.getStatus.getCode == gRPCStatus.Code.ALREADY_EXISTS =>
          Done
      })

  private def transformDestinationEvent(destinationEvent: DestinationEvent): PubsubMessage = {
    val attributes = Map(
        "ce-id" -> destinationEvent.event.id,
        "ce-source" -> destinationEvent.event.source,
        "ce-specversion" -> destinationEvent.event.specversion,
        "ce-type" -> destinationEvent.event.`type`,
        "ce-datacontenttype" -> destinationEvent.event.datacontenttype
      ) ++
      destinationEvent.event.subject.map(s => "ce-subject" -> s) ++
      destinationEvent.event.dataschema.map(d => "ce-dataschema" -> d) ++
      destinationEvent.event.time.map(t => "ce-time" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t))

    PubsubMessage(destinationEvent.event.data.getOrElse(ByteString.EMPTY), attributes)
  }

  private[this] final def createUsingCrdManagedDestination(topic: String): Future[Done] =
    throw new IllegalStateException("NOT IMPLEMENTED YET") // FIXME IMPLEMENT THIS: create CRD-requests

  private def futureKillSwitch(future: Future[KillSwitch])(implicit ec: ExecutionContext): KillSwitch = new KillSwitch {
    override def shutdown(): Unit = future.foreach(_.shutdown())

    override def abort(ex: Throwable): Unit = future.foreach(_.abort(ex))
  }
}
