package com.lightbend.statefulserverless.operator

import java.time.Instant

import play.api.libs.json.{Format, JsObject, Json}
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, EnvVar, ListResource, ResourceDefinition}
import skuber.json.format._

object EventSourcedJournal {

  type Resource = CustomResource[EventSourcedJournal.Spec, EventSourcedJournal.Status]
  type ResourceList = ListResource[Resource]

  case class Spec(`type`: String, deployment: String, config: JsObject)

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Status(
    specHash: Option[String],
    image: Option[String],
    sidecarEnv: Option[Seq[EnvVar]],
    reason: Option[String],
    lastApplied: Option[Instant]
  )

  object Status {
    implicit val format: Format[Status] = Json.format
  }

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