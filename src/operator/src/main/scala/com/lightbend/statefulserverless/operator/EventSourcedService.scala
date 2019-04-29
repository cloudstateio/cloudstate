package com.lightbend.statefulserverless.operator

import play.api.libs.functional.syntax._
import play.api.libs.json._
import skuber.json.format._
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{Container, CustomResource, EnvFromSource, EnvVar, Lifecycle, ListResource, ObjectMeta, Pod, Probe, Resource, ResourceDefinition, SecurityContext, Volume}

object EventSourcedService {

  type Resource = CustomResource[Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(replicas: Int, journal: Journal, template: Template)

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Template(metadata: Option[ObjectMeta], spec: TemplateSpec)

  object Template {
    implicit val format: Format[Template] = Json.format
  }

  case class TemplateSpec(containers: List[Container])

  object TemplateSpec {
    implicit val containerFormat: Format[Container] = (
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
        (JsPath \ "terminationMessagePolicy").formatNullableEnum(Container.TerminationMessagePolicy)  and
        (JsPath \ "imagePullPolicy").formatEnum(Container.PullPolicy, Some(Container.PullPolicy.IfNotPresent)) and
        (JsPath \ "securityContext").formatNullable[SecurityContext] and
        (JsPath \ "envFrom").formatMaybeEmptyList[EnvFromSource] and
        (JsPath \ "stdin").formatNullable[Boolean] and
        (JsPath \ "stdinOnce").formatNullable[Boolean] and
        (JsPath \ "tty").formatNullable[Boolean] and
        (JsPath \ "volumeDevices").formatMaybeEmptyList[Volume.Device]
      )(Container.apply _, unlift(Container.unapply))

    implicit val format: Format[TemplateSpec] = Json.format
  }

  case class Journal(name: String, config: Option[JsObject])

  object Journal {
    implicit val format: Format[Journal] = Json.format
  }

  case class Status(
    error: Option[String]
  )

  object Status {
    implicit val format: Format[Status] = Json.format
  }


  implicit val eventSourcedServiceResourceDefinition = ResourceDefinition[Resource](
    group = "statefulserverless.lightbend.com",
    version = "v1alpha1",
    kind = "EventSourcedService",
    shortNames = List("ess"),
    subresources = Some(Subresources()
      .withStatusSubresource
    )
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Resource]

  val crd = CustomResourceDefinition[Resource]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
}