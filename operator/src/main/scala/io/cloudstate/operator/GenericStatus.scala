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

package io.cloudstate.operator

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
    reason: Option[String] = None,
    message: Option[String] = None,
    severity: Option[String] = None,
    lastUpdateTime: Option[ZonedDateTime] = None,
    lastTransitionTime: Option[ZonedDateTime] = None
)

object Condition {
  private implicit val timeFormat: Format[ZonedDateTime] =
    Format(skuber.json.format.timeReads, skuber.json.format.timewWrites)
  implicit val format: Format[Condition] = Json.format
}
