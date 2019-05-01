package com.lightbend.statefulserverless.operator

import play.api.libs.json._
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ListResource, ResourceDefinition}

object EventSourcedServiceConfiguration {

  type Resource = CustomResource[Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(
    replicas: Int,
    journal: EventSourcedService.Journal,
    template: EventSourcedService.Template
  )

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Status(
    appliedSpecHash: Option[String],
    // This field may be updated by the journal operator
    journalConfigHash: Option[String],
    appliedJournalConfigHash: Option[String],
    reason: Option[String]
  )

  object Status {
    implicit val format: Format[Status] = Json.format
  }


  implicit val eventSourcedServiceConfigurationResourceDefinition = ResourceDefinition[Resource](
    group = "statefulserverless.lightbend.com",
    version = "v1alpha1",
    kind = "EventSourcedServiceConfiguration",
    shortNames = List("essc"),
    subresources = Some(Subresources()
      .withStatusSubresource
    )
  )

  implicit val statusSubEnabled = CustomResource.statusMethodsEnabler[Resource]

  val crd = CustomResourceDefinition[Resource]

  def apply(name: String, spec: Spec) = CustomResource[Spec, Status](spec).withName(name)
}