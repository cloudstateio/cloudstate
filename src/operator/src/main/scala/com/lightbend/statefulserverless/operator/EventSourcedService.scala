package com.lightbend.statefulserverless.operator

import play.api.libs.json.{Format, JsObject, JsValue, Json}
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ListResource, Pod, ResourceDefinition}


object EventSourcedService {

  type Resource = CustomResource[Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(replicas: Int, journal: Journal, template: Pod.Template.Spec)

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Journal(name: String, config: Option[JsObject])

  object Journal {
    implicit val format: Format[Journal] = Json.format
  }

  case class Status()

  object Status {
    implicit val format: Format[Status] = Json.format
  }


  implicit val statusFmt: Format[Status] = Json.format[Status]

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