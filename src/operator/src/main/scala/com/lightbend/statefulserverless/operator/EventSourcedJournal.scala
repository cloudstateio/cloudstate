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

import play.api.libs.json.{Format, JsObject, Json}
import skuber.ResourceSpecification.Subresources
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ListResource, ResourceDefinition}

object EventSourcedJournal {

  type Resource = CustomResource[EventSourcedJournal.Spec, Status]
  type ResourceList = ListResource[Resource]

  case class Spec(`type`: String, deployment: String, config: JsObject)

  object Spec {
    implicit val format: Format[Spec] = Json.format
  }

  case class Status(conditions: Option[List[Condition]], specHash: Option[String])

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