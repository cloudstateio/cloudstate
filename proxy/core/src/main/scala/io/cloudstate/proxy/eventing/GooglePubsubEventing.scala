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

/*
import com.typesafe.config.{Config, ConfigFactory}
import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import io.cloudstate.proxy.Serve.CommandHandler
import io.cloudstate.proxy.EntityDiscoveryManager.ServableEntity
import io.cloudstate.proxy.entity.UserFunctionCommand
import io.cloudstate.eventing.Eventing
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.{ByteString => ProtobufByteString}
import com.google.protobuf.Descriptors.MethodDescriptor
import io.grpc.{
  CallCredentials => gRPCCallCredentials,
  Status => gRPCStatus,
  StatusRuntimeException => gRPCStatusRuntimeException
}
import io.grpc.auth.MoreCallCredentials
import com.google.auth.oauth2.GoogleCredentials
import com.google.pubsub.v1.pubsub.{
  AcknowledgeRequest,
  PublishRequest,
  PublishResponse,
  PubsubMessage,
  ReceivedMessage,
  StreamingPullRequest,
  Subscription,
  Topic,
  PublisherClient => ScalaPublisherClient,
  SubscriberClient => ScalaSubscriberClient
}
import java.util.Collections

import akka.util.ByteString
import io.cloudstate.protocol.entity.Metadata

import scala.util.Try
import scala.concurrent.{Future, Promise}
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

class GCPubsubEventingSupport(config: Config, materializer: Materializer) extends EventingSupport {
  import GCPubsubEventingSupport._

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
    implicit val m = materializer
    implicit val s = materializer.system
    implicit val d = s.dispatcher

    val clientSettings = settings.createClientSettings()

    // We're reusing the same clients for all communication
    val subscriberClient = ScalaSubscriberClient(clientSettings)
    val publisherClient = ScalaPublisherClient(clientSettings)

    // Make sure that we don't leak connections
    s.registerOnTermination(subscriberClient.close())
    s.registerOnTermination(publisherClient.close())

    (subscriberClient, publisherClient)
  }

  private[this] val batchResults =
    if (downstreamBatchDeadline > 0.seconds || downstreamBatchSize > 1) {
      Flow[ProtobufAny]
        .map(any => PubsubMessage(data = any.toByteString))
        .groupedWithin(downstreamBatchSize, downstreamBatchDeadline)
    } else Flow[ProtobufAny].map(any => PubsubMessage(data = any.toByteString) :: Nil)

  private[this] final def createManualSource(
      subscription: String,
      commandHandler: CommandHandler
  ): Source[UserFunctionCommand, Future[Cancellable]] = {
    val cancellable = Promise[Cancellable]

    val request =
      StreamingPullRequest(subscription = subscription, streamAckDeadlineSeconds = upstreamAckDeadlineSeconds)

    val pull = Source
      .single(request)
      .concat(
        Source.tick(0.seconds, pollInterval, request.withSubscription("")).mapMaterializedValue(cancellable.success)
      )

    val ackSink =
      Flow[ReceivedMessage]
        .map(_.ackId)
        .groupedWithin(10, upstreamAckDeadline / 2) // TODO adjust these
        .mapAsyncUnordered(1 /*parallelism*/ )(
          ackIds => subscriberClient.acknowledge(AcknowledgeRequest(subscription = subscription, ackIds = ackIds))
        )
        .toMat(Sink.ignore)(Keep.right)

    subscriberClient // FIXME add retries, backoff etc
      .streamingPull(pull) // TODO Consider Source.repeat(()).flatMapConcat(_ => subscriberClient.streamingPull(pull))
      .mapConcat(_.receivedMessages.toVector) // Note: receivedMessages is most likely a Vector already due to impl, so should be a noop
      .alsoTo(ackSink) // at-most-once // FIXME Add stats generation/collection so we can track progress here
      .collect({
        case ReceivedMessage(_, Some(msg), _) =>
          commandHandler
            .deserialize(Metadata.defaultInstance)(ByteString.fromByteBuffer(msg.data.asReadOnlyByteBuffer()))
      }) // TODO - investigate ProtobufAny.fromJavaAny(PbAnyJava.parseFrom(msg.data))
      .mapMaterializedValue(_ => cancellable.future)
  }

  private[this] final def createByProxyManagedSource(
      sourceName: String,
      subscription: String,
      commandHandler: CommandHandler
  ): Source[UserFunctionCommand, Future[Cancellable]] =
    Source
      .setup { (mat, attrs) =>
        val topic = s"projects/${projectId}/topics/${sourceName}"
        implicit val ec = mat.system.dispatcher
        val t = Topic(topic)
        val s = Subscription(
          name = subscription,
          topic = topic,
          pushConfig = None,
          ackDeadlineSeconds = upstreamAckDeadlineSeconds,
          retainAckedMessages = false,
          messageRetentionDuration = None // TODO configure this?
        )

        Source
          .fromFutureSource(
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
            } yield createManualSource(subscription, commandHandler)
          )
      }
      .mapMaterializedValue(_.flatten.flatten)

  private[this] final def createUsingCrdManagedSource(
      sourceName: String,
      subscription: String,
      commandHandler: CommandHandler
  ): Source[UserFunctionCommand, Future[Cancellable]] =
    throw new IllegalStateException("NOT IMPLEMENTED YET") // FIXME IMPLEMENT THIS: create CRD-requests

  override final def createSource(sourceName: String,
                                  commandHandler: CommandHandler): Source[UserFunctionCommand, Future[Cancellable]] = {
    val subscription = s"projects/${projectId}/subscriptions/${sourceName}_${commandHandler.fullCommandName}"
    manageTopicsAndSubscriptions match {
      case MANUALLY => createManualSource(subscription, commandHandler)
      case USING_CRD => createUsingCrdManagedSource(sourceName, subscription, commandHandler)
      case BY_PROXY => createByProxyManagedSource(sourceName, subscription, commandHandler)
    }
  }

  private[this] final def createDestination(topic: String): Flow[ProtobufAny, AnyRef, NotUsed] =
    batchResults
      .mapAsyncUnordered(1 /*parallelism*/ )(
        batch =>
          publisherClient.publish(PublishRequest(topic = topic, messages = batch)) // FIXME add retries, backoff etc
      )

  private[this] final def createByProxyManagedDestination(topic: String): Flow[ProtobufAny, AnyRef, NotUsed] =
    Flow
      .setup { (mat, attrs) =>
        implicit val ec = mat.system.dispatcher
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

  //FIXME Add stats generation/collection so we can track progress here
  override final def createDestination(destinationName: String,
                                       handler: CommandHandler): Flow[ProtobufAny, AnyRef, NotUsed] =
    if (destinationName == "")
      Flow[ProtobufAny]
    else {
      val topic = s"projects/${projectId}/topics/${destinationName}"
      manageTopicsAndSubscriptions match {
        case MANUALLY => createDestination(topic = topic)
        case USING_CRD => createUsingCrdManagedDestination(topic = topic)
        case BY_PROXY => createByProxyManagedDestination(topic = topic)
      }
    }
}
 */
