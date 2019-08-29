package io.cloudstate.operator

import java.time.ZonedDateTime

import skuber.ResourceSpecification.Subresources
import skuber.{Container, CustomResource, EnvFromSource, EnvVar, Lifecycle, ListResource, Pod, Probe, Resource, ResourceDefinition, SecurityContext, Volume}
import skuber.apiextensions.CustomResourceDefinition
import play.api.libs.functional.syntax._
import play.api.libs.json._
import skuber.json.format._

object StatefulService {

  import OperatorConstants._

  type Resource = CustomResource[Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(
    containers: List[Container],
    volumes: Option[List[Volume]],
    serviceAccountName: Option[String],
    autoscaling: Option[Autoscaling],
    journal: Option[Journal],
    sidecarResources: Option[Resource.Requirements],
    sidecarJvmMemory: Option[String],
    nodeSelector: Option[Map[String, String]],
    tolerations: Option[List[Pod.Toleration]]
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

  case class Autoscaling(
    enabled: Option[Boolean],
    userFunctionTargetConcurrency: Option[Int],
    requestTargetConcurrency: Option[Int],
    targetConcurrencyWindow: Option[String],
    scaleUpStableDeadline: Option[String],
    scaleDownStableDeadline: Option[String],
    requestRateThresholdFactor: Option[Double],
    requestRateWindow: Option[String]
  )

  object Autoscaling {
    implicit val format: Format[Autoscaling] = Json.format
  }

  case class Journal(
    name: String,
    config: Option[JsObject]
  )

  object Journal {
    implicit val format: Format[Journal] = Json.format
  }

  case class Status(
    conditions: List[Condition]
  )

  object Status {
    implicit val format: Format[Status] =
      (__ \ "conditions").formatNullable[List[Condition]]
        .inmap[Status](c => Status(c.getOrElse(Nil)), s => Some(s.conditions))
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

  implicit val eventSourcedServiceResourceDefinition = ResourceDefinition[Resource](
    group = CloudStateGroup,
    version = CloudStateApiVersionNumber,
    kind = StatefulServiceKind,
    shortNames = List("ess"),
    subresources = Some(Subresources()
      .withStatusSubresource
    )
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Resource]

  val crd = CustomResourceDefinition[Resource]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
}
