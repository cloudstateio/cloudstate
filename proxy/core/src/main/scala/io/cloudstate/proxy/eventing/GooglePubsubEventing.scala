package io.cloudstate.proxy.eventing

import com.typesafe.config.{Config, ConfigFactory}
import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Cancellable}
import akka.grpc.GrpcClientSettings
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches, Materializer, OverflowStrategy, SourceShape}
import akka.stream.scaladsl.{Broadcast, Concat, Flow, GraphDSL, Keep, Merge, RestartSink, RestartSource, Sink, Source}
import io.cloudstate.eventing.{EventSource => EventSourceProto, EventDestination => EventDestinationProto}
import com.google.protobuf.any.{Any => ProtobufAny}
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

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity}
import akka.util.ByteString
import io.cloudstate.eventing

import scala.util.Try
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
        .map(any => PubsubMessage(data = any.toByteString))
        .groupedWithin(downstreamBatchSize, downstreamBatchDeadline)
    } else Flow[ProtobufAny].map(any => PubsubMessage(data = any.toByteString) :: Nil)

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
          .map(transformReceivedMessage)

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

    override def run(flow: Flow[SourceEvent[String], String, _]): KillSwitch = {
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
      manageTopicsAndSubscriptions match {
        case MANUALLY => runManualFlow(subscription, flow)
        case USING_CRD => futureKillSwitch(createUsingCrdManagedFlow(consumerGroup, subscription, flow))
        case BY_PROXY => futureKillSwitch(runByProxyManagedFlow(consumerGroup, subscription, flow))
      }
    }
  }

  private def transformReceivedMessage(receivedMessage: ReceivedMessage): SourceEvent[String] = {
    val message =
      receivedMessage.message.getOrElse(throw new IllegalArgumentException("Received message has no message"))
    // Using the spec here, which was not merged, to handle cloudevents:
    // https://github.com/google/knative-gcp/pull/1
    // case sensitivity?
    val contentType = message.attributes.get("Content-Type") match {
      case Some("application/cloudevents+json") =>
        // Todo: handle structured cloudevents
        throw new UnsupportedOperationException("CloudEvents structured binding not yet supported")

      case defaultCt if message.attributes.contains("ce-specversion") =>
        // This is a CloudEvents binary message
        message.attributes
          .get("ce-datacontenttype")
          .orElse(defaultCt)
          .flatMap(ct => ContentType.parse(ct).toOption)
          .getOrElse(ContentTypes.`application/octet-stream`)

      case Some(other) => ContentType.parse(other).right.getOrElse(ContentTypes.`application/octet-stream`)
      case None => ContentTypes.`application/octet-stream`
    }

    val body = ByteString.fromByteBuffer(message.data.asReadOnlyByteBuffer())

    SourceEvent(HttpEntity.Strict(contentType, body), receivedMessage.ackId)
  }

  override def supportsDestination: Boolean = true

  override def createDestination(destination: eventing.EventDestination): EventDestination = new EventDestination {
    override def eventStreamOut: Flow[DestinationEvent, AnyRef, NotUsed] = {
      val topic = destination.destination match {
        case EventDestinationProto.Destination.Topic(topic) =>
          s"projects/$projectId/topic/$topic"
        case other =>
          throw new IllegalArgumentException(s"Google PubSub source unable to be used to server $other")
      }

      manageTopicsAndSubscriptions match {
        case MANUALLY => createDestination(topic = topic)
        case USING_CRD => createUsingCrdManagedDestination(topic = topic)
        case BY_PROXY => createByProxyManagedDestination(topic = topic)
      }
    }
    override def emitSingle(destinationEvent: DestinationEvent): Future[Done] = ???
  }

  private[this] final def createDestination(topic: String): Flow[DestinationEvent, AnyRef, NotUsed] =
    batchResults
      .mapAsyncUnordered(1 /*parallelism*/ )(
        batch =>
          publisherClient.publish(PublishRequest(topic = topic, messages = batch)) // FIXME add retries, backoff etc
      )

  private[this] final def createByProxyManagedDestination(topic: String): Flow[ProtobufAny, AnyRef, NotUsed] =
    Flow
      .setup { (mat, attrs) =>
        val destination = createDestination(topic = topic)
        val t = Topic(topic)
        val f = publisherClient
          .createTopic(t)
          .recover({
            case ex: gRPCStatusRuntimeException if ex.getStatus.getCode == gRPCStatus.Code.ALREADY_EXISTS =>
              t
          })
          .map(_ => destination)

        Flow.lazyInitAsync(() => f)
      }
      .mapMaterializedValue(_ => NotUsed)

  private[this] final def createUsingCrdManagedDestination(topic: String): Flow[ProtobufAny, AnyRef, NotUsed] =
    throw new IllegalStateException("NOT IMPLEMENTED YET") // FIXME IMPLEMENT THIS: create CRD-requests

  private def futureKillSwitch(future: Future[KillSwitch])(implicit ec: ExecutionContext): KillSwitch = new KillSwitch {
    override def shutdown(): Unit = future.foreach(_.shutdown())
    override def abort(ex: Throwable): Unit = future.foreach(_.abort(ex))
  }
}
