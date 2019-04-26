package com.lightbend.statefulserverless.operator

import play.api.libs.json.{Format, JsObject, Json}
import skuber.Pod.Template
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ListResource, ResourceDefinition}


object EventSourcedJournal {

  type Resource = CustomResource[EventSourcedService.Spec, EventSourcedService.Status]
  type ResourceList = ListResource[Resource]

  case class Spec(`type`: String, deployment: String, config: JsObject)

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Status()

  object Status {
    implicit val format: Format[Status] = Json.format
  }


  implicit val statusFmt: Format[Status] = Json.format[Status]

  implicit val eventSourcedJournalResourceDefinition = ResourceDefinition[Resource](
    group = "statefulserverless.lightbend.com",
    version = "v1alpha1",
    kind = "EventSourcedJournal",
    shortNames = List("esj"),
    subresources = Some(Subresources()
      .withStatusSubresource
    )
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Resource]

  val crd = CustomResourceDefinition[Resource]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
}