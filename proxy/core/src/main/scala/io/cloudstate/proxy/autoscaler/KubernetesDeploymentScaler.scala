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

package io.cloudstate.proxy.autoscaler

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, Props, Status, Timers}
import akka.discovery.kubernetes.Settings
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpEntity, HttpMethod, HttpMethods, HttpRequest, MessageEntity, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.management.cluster.bootstrap.ClusterBootstrapSettings
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import com.typesafe.sslconfig.ssl.TrustStoreConfig
import spray.json.{DefaultJsonProtocol, JsonFormat, RootJsonFormat}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NonFatal

object KubernetesDeploymentScaler {
  private final case class DeploymentList(
      items: List[Deployment]
  )

  private final case class Deployment(
      metadata: Metadata,
      spec: DeploymentSpec,
      status: Option[DeploymentStatus]
  )

  private final case class Metadata(
      name: String,
      namespace: String
  )

  private final case class DeploymentSpec(
      replicas: Int
  )

  private final case class DeploymentStatus(
      readyReplicas: Option[Int],
      conditions: Option[List[DeploymentCondition]]
  )

  /**
   * This gives the status of upgrades.
   */
  private final val DeploymentProgressingConditionType = "Progressing"

  /**
   * There are at least three reasons for the progressing type,
   * NewReplicaSetAvailable - the deployment is ready and stable, no upgrade in progress
   * NewReplicaSetCreated - an upgrade has started with the new replica set being created
   * NewReplicaSetUpdated - an upgrade is in progress with the new replica set providing some nodes
   * So, if the reason isn't the first one, then we're currently upgrading
   */
  private final val DeploymentProgressingReasonNotUpgrading = "NewReplicaSetAvailable"

  private final case class DeploymentCondition(
      `type`: String,
      reason: String
  )

  private final case class Scale(
      metadata: Metadata,
      spec: ScaleSpec,
      status: Option[ScaleStatus]
  )

  private final case class ScaleSpec(
      replicas: Option[Int]
  )

  private final case class ScaleStatus(
      replicas: Option[Int]
  )

  private object JsonFormat extends SprayJsonSupport with DefaultJsonProtocol {
    // todo don't use the reflection based methods to deserialize here since it just creates more work for us
    // in the graal reflection config
    implicit val metadataFormat: JsonFormat[Metadata] = jsonFormat2(Metadata)
    implicit val scaleSpecFormat: JsonFormat[ScaleSpec] = jsonFormat1(ScaleSpec)
    implicit val scaleStatusFormat: JsonFormat[ScaleStatus] = jsonFormat1(ScaleStatus)
    implicit val scaleFormat: RootJsonFormat[Scale] = jsonFormat3(Scale)
    implicit val deploymentConditionFormat: JsonFormat[DeploymentCondition] = jsonFormat2(DeploymentCondition)
    implicit val deploymentStatusFormat: JsonFormat[DeploymentStatus] = jsonFormat2(DeploymentStatus)
    implicit val deploymentSpecFormat: JsonFormat[DeploymentSpec] = jsonFormat1(DeploymentSpec)
    implicit val deploymentFormat: RootJsonFormat[Deployment] = jsonFormat3(Deployment)
    implicit val deploymentListFormat: RootJsonFormat[DeploymentList] = jsonFormat1(DeploymentList)
  }

  private case object Tick extends DeadLetterSuppression

  def props(autoscaler: ActorRef): Props = Props(new KubernetesDeploymentScaler(autoscaler))
}

class KubernetesDeploymentScaler(autoscaler: ActorRef) extends Actor with ActorLogging with Timers {

  import KubernetesDeploymentScaler._
  import KubernetesDeploymentScaler.JsonFormat._

  import akka.pattern.pipe

  implicit val ec = context.dispatcher
  implicit val mat: Materializer = ActorMaterializer()

  // A lot of the below is copied shamelessly from KubernetesApiServiceDiscovery
  private[this] final val http = Http()(context.system)
  private[this] final val kubernetesSettings = Settings(context.system)
  private[this] final val clusterBootstrapSettings =
    ClusterBootstrapSettings(context.system.settings.config, context.system.log)
  private[this] final val httpsTrustStoreConfig =
    TrustStoreConfig(data = None, filePath = Some(kubernetesSettings.apiCaPath)).withStoreType("PEM")
  private[this] final val httpsConfig =
    AkkaSSLConfig()(context.system).mapSettings(
      s => s.withTrustManagerConfig(s.trustManagerConfig.withTrustStoreConfigs(Seq(httpsTrustStoreConfig)))
    )
  private[this] final val httpsContext = http.createClientHttpsContext(httpsConfig)
  private[this] final val apiToken = readConfigVarFromFilesystem(kubernetesSettings.apiTokenPath, "api-token") getOrElse ""
  private[this] final val deployNamespace = kubernetesSettings.podNamespace orElse
    readConfigVarFromFilesystem(kubernetesSettings.podNamespacePath, "pod-namespace") getOrElse "default"
  private[this] final val serviceName = clusterBootstrapSettings.contactPointDiscovery.serviceName getOrElse {
      throw new RuntimeException("No service name defined")
    }
  private[this] final val host = sys.env(kubernetesSettings.apiServiceHostEnvName)
  private[this] final val port = sys.env(kubernetesSettings.apiServicePortEnvName).toInt
  private[this] final val appsV1ApiPath = Uri.Path / "apis" / "apps" / "v1" / "namespaces" / deployNamespace

  // Rather than polling, we could watch the resource
  timers.startPeriodicTimer("tick", Tick, 20.seconds)
  self ! Tick

  private def running(deployment: Deployment): Receive = receive orElse {
    case Autoscaler.Scale(name, scale) =>
      if (deployment.spec.replicas != scale) {
        for {
          entity <- Marshal(Scale(Metadata(name, deployNamespace), ScaleSpec(Some(scale)), None)).to[MessageEntity]
          response <- makeRequest[Deployment](
            buildRequest(appsV1ApiPath / "deployments" / name / "scale", HttpMethods.PUT)
              .withEntity(entity)
          )
        } yield {
          self ! response
        }
      }
  }

  override def receive: Receive = {

    case dep: Deployment =>
      updateDeployment(dep)

    case DeploymentList(dep :: Nil) =>
      updateDeployment(dep)

    case DeploymentList(Nil) =>
      log.warning(s"No deployments found that match selector '{}'", kubernetesSettings.podLabelSelector(serviceName))

    case DeploymentList(deps) =>
      log.warning(
        s"Got back multiple deployments that match selector '{}': {}, using the first one.",
        kubernetesSettings.podLabelSelector(serviceName),
        deps.map(_.metadata.name).mkString(",")
      )

      updateDeployment(deps.head)

    case Tick =>
      val query = Uri.Query("labelSelector" -> kubernetesSettings.podLabelSelector(serviceName))
      makeRequest[DeploymentList](buildRequest(appsV1ApiPath / "deployments", HttpMethods.GET, Some(query))) pipeTo self

    case Status.Failure(cause) =>
      log.error(cause, "Error making request on Kubernetes API")

  }

  private def updateDeployment(dep: Deployment): Unit = {
    autoscaler ! Autoscaler.Deployment(
      name = dep.metadata.name,
      ready = dep.status.flatMap(_.readyReplicas).getOrElse(0),
      scale = dep.spec.replicas,
      upgrading = isUpgrading(dep)
    )
    context become running(dep)
  }

  private def isUpgrading(dep: Deployment) =
    !dep.status.exists(
      _.conditions.exists(
        _.exists(
          condition =>
            condition.`type` == DeploymentProgressingConditionType &&
            condition.reason == DeploymentProgressingReasonNotUpgrading
        )
      )
    )

  /**
   * This uses blocking IO, and so should only be used to read configuration at startup.
   */
  private def readConfigVarFromFilesystem(path: String, name: String): Option[String] = {
    val file = Paths.get(path)
    if (Files.exists(file)) {
      try {
        Some(new String(Files.readAllBytes(file), "utf-8"))
      } catch {
        case NonFatal(e) =>
          log.error(e, "Error reading {} from {}", name, path)
          None
      }
    } else {
      log.warning("Unable to read {} from {} because it doesn't exist.", name, path)
      None
    }
  }

  private def makeRequest[T](
      request: HttpRequest
  )(implicit unmarshaller: Unmarshaller[HttpEntity.Strict, T]): Future[T] = {
    log.debug("Making request {}", request)
    for {
      response <- http.singleRequest(request, httpsContext)
      entity <- response.entity.toStrict(5.seconds)
      deserialized <- {
        response.status match {
          case StatusCodes.OK =>
            log.debug("Kubernetes API entity: [{}]", entity.data.utf8String)
            val unmarshalled = Unmarshal(entity).to[T]
            unmarshalled.failed.foreach { t =>
              log.warning(
                "Failed to unmarshal Kubernetes API response.  Status code: [{}]; Response body: [{}]. Ex: [{}]",
                response.status.value,
                entity,
                t.getMessage
              )
            }
            unmarshalled
          case StatusCodes.Forbidden =>
            Unmarshal(entity).to[String].foreach { body =>
              log.warning("Forbidden to communicate with Kubernetes API server; check RBAC settings. Response: [{}]",
                          body)
            }
            Future.failed(
              new RuntimeException("Forbidden when communicating with the Kubernetes API. Check RBAC settings.")
            )
          case other =>
            Unmarshal(entity).to[String].foreach { body =>
              log.warning(
                "Non-200 when communicating with Kubernetes API server. Status code: [{}]. Response body: [{}]",
                other,
                body
              )
            }

            Future.failed(new RuntimeException(s"Non-200 from Kubernetes API server: $other"))
        }

      }
    } yield deserialized
  }

  private def buildRequest(path: Uri.Path, method: HttpMethod, query: Option[Uri.Query] = None) = {
    val uriPath = Uri.from(scheme = "https", host = host, port = port).withPath(path)
    val uri = query.fold(uriPath)(q => uriPath.withQuery(q))

    HttpRequest(method = method, uri = uri, headers = Seq(Authorization(OAuth2BearerToken(apiToken))))
  }
}
