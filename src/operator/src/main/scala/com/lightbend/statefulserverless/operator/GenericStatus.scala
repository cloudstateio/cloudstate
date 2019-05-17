package com.lightbend.statefulserverless.operator

import java.time.ZonedDateTime

import play.api.libs.json.{Format, Json}

case class GenericStatus(
  conditions: Option[List[Condition]]
)

object GenericStatus {
  implicit val format: Format[GenericStatus] = Json.format
}

case class Condition(
  `type`: String,
  status: String,
  reason: Option[String],
  message: Option[String],
  lastUpdateTime: Option[ZonedDateTime] = None,
  lastTransitionTime: Option[ZonedDateTime] = None
)

object Condition {
  private implicit val timeFormat: Format[ZonedDateTime] = Format(skuber.json.format.timeReads, skuber.json.format.timewWrites)
  implicit val format: Format[Condition] = Json.format
}
