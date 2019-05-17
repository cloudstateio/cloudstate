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

package com.lightbend.statefulserverless.operator

import java.time.ZonedDateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._
import skuber.json.format._
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{Container, CustomResource, EnvFromSource, EnvVar, Lifecycle, ListResource, Probe, Resource, ResourceDefinition, SecurityContext, Volume}

object KnativeRevision {

  import OperatorConstants._

  type Resource = CustomResource[Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(
    containers: List[Container],
    volumes: List[Volume],
    serviceAccountName: Option[String],
    containerConcurrency: Option[Long],
    timeoutSeconds: Option[Long],
    deployer: Option[Deployer]
  )

  object Spec {
    implicit val containerFormat: Format[Container] = {

      // todo see if this is actually necessary
      // Using our own format here so that we can make name optional.

      // Also, we want to default imagePullPolicy based on whether the image is latest or not, like Kubernetes.
      // So we need to be a little special in how we deal with that.
      val imagePullPolicyReads = (
        (JsPath \ "image").read[String] and
          (JsPath \ "imagePullPolicy").formatNullableEnum(Container.PullPolicy)
        ) ((image, pullPolicy) => pullPolicy.getOrElse {
        if (image.endsWith(":latest")) Container.PullPolicy.Always else Container.PullPolicy.IfNotPresent
      })
      val imagePullPolicyFormat = OFormat(imagePullPolicyReads, (JsPath \ "imagePullPolicy").formatEnum(Container.PullPolicy))

      (
        (JsPath \ "name").formatWithDefault[String]("") and
          (JsPath \ "image").format[String] and
          (JsPath \ "command").formatMaybeEmptyList[String] and
          (JsPath \ "args").formatMaybeEmptyList[String] and
          (JsPath \ "workingDir").formatNullable[String] and
          (JsPath \ "ports").formatMaybeEmptyList[Container.Port] and
          (JsPath \ "env").formatMaybeEmptyList[EnvVar] and
          (JsPath \ "resources").formatNullable[Resource.Requirements] and
          (JsPath \ "volumeMounts").formatMaybeEmptyList[Volume.Mount] and
          (JsPath \ "livenessProbe").formatNullable[Probe] and
          (JsPath \ "readinessProbe").formatNullable[Probe] and
          (JsPath \ "lifecycle").formatNullable[Lifecycle] and
          (JsPath \ "terminationMessagePath").formatNullable[String] and
          (JsPath \ "terminationMessagePolicy").formatNullableEnum(Container.TerminationMessagePolicy) and
          imagePullPolicyFormat and
          (JsPath \ "securityContext").formatNullable[SecurityContext] and
          (JsPath \ "envFrom").formatMaybeEmptyList[EnvFromSource] and
          (JsPath \ "stdin").formatNullable[Boolean] and
          (JsPath \ "stdinOnce").formatNullable[Boolean] and
          (JsPath \ "tty").formatNullable[Boolean] and
          (JsPath \ "volumeDevices").formatMaybeEmptyList[Volume.Device]
        ) (Container.apply, unlift(Container.unapply))
    }


    implicit val format: Format[Spec] = Json.format
  }

  sealed trait Deployer

  object Deployer {
    implicit val reads: Reads[Deployer] = {
      val configPath = __ \ "config"
      (__ \ "name").read[String].flatMap {
        case "KnativeServing" => Reads.pure(KnativeServingDeployer)
        case OperatorConstants.EventSourcedDeployerName =>
          configPath.read[EventSourcedDeployer].map(identity)
        case other =>
          configPath.readNullable[JsValue]
            .map(config => UnknownDeployer(other, config))
      }
    }

    implicit val writes: Writes[Deployer] = (
      (__ \ "name").write[String] and
        (__ \ "config").writeNullable[JsValue]
      )((dep: Deployer) => dep match {
      case KnativeServingDeployer => ("KnativeServing", None)
      case es: EventSourcedDeployer => (OperatorConstants.EventSourcedDeployerName, Some(Json.toJson(es)))
      case UnknownDeployer(name, config) => (name, config)
    })
  }

  case object KnativeServingDeployer extends Deployer

  case class EventSourcedDeployer(
    journal: Journal,
    sidecarResources: Option[Resource.Requirements],
    sidecarJvmMemory: Option[String]
  ) extends Deployer

  object EventSourcedDeployer {
    implicit val format: Format[EventSourcedDeployer] = Json.format
  }

  case class UnknownDeployer(name: String, config: Option[JsValue]) extends Deployer

  case class Journal(
    name: String,
    config: Option[JsObject]
  )

  object Journal {
    implicit val format: Format[Journal] = Json.format
  }

  case class Status(
    observedGeneration: Option[Long],
    conditions: List[Condition],
    serviceName: Option[String],
    logUrl: Option[String],
    imageDigest: Option[String]
  )

  object Status {
    implicit val format: Format[Status] = (
      (__ \ "observedGeneration").formatNullable[Long] and
        (__ \ "conditions").formatNullable[List[Condition]]
          .inmap[List[Condition]](_.getOrElse(Nil), Some(_)) and
        (__ \ "serviceName").formatNullable[String] and
        (__ \ "logUrl").formatNullable[String] and
        (__ \ "imageDigest").formatNullable[String]
      ) (Status.apply, unlift(Status.unapply))
  }

  case class Condition(
    `type`: String,
    status: String,
    severity: Option[String] = None,
    lastTransitionTime: Option[ZonedDateTime] = None,
    reason: Option[String] = None,
    message: Option[String] = None
  )

  object Condition {
    private implicit val timeFormat: Format[ZonedDateTime] = Format(skuber.json.format.timeReads, skuber.json.format.timewWrites)
    implicit val format: Format[Condition] = Json.format
  }

  implicit val knativeRevisionResourceDefinition = ResourceDefinition[Resource](
    group = KnativeServingGroup,
    version = KnativeServingVersion,
    kind = RevisionKind,
    shortNames = List("rev"),
    subresources = Some(Subresources()
      .withStatusSubresource
    )
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Resource]

  val crd = CustomResourceDefinition[Resource]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
}