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

import java.util.Locale

object OperatorConstants {
  val StatefulServerlessGroup = "statefulserverless.lightbend.com"
  val JournalLabel = s"$StatefulServerlessGroup/journal"

  val KnativeServingGroup = "serving.knative.dev"
  val KnativeServingVersion = "v1alpha1"
  val KnativeServingApiVersion = s"$KnativeServingGroup/$KnativeServingVersion"
  val RevisionKind = "Revision"
  val RevisionLabel = s"$KnativeServingGroup/${RevisionKind.toLowerCase(Locale.ROOT)}"
  val ConfigurationLabel = s"$KnativeServingGroup/configuration"
  val ServiceLabel = s"$KnativeServingGroup/service"
  val RevisionUidLabel = s"$KnativeServingGroup/revisionUID"
  val LastPinnedLabel = s"$KnativeServingGroup/lastPinned"
  val DeployerKind = "Deployer"

  val RevisionConditionResourcesAvailable = "ResourcesAvailable"
  val RevisionConditionEventSourcedOk = "EventSourcedOk"

  val RevisionConditionResourcesAvailableNotOwned = "NotOwned"

  val EventSourcedDeployerName = "EventSourced"

  val JournalConditionType = "JournalValid"

  val TrueStatus = "True"
  val FalseStatus = "False"
  val UnknownStatus = "Unknown"

  val PodReaderRoleName = "statefulserverless-pod-reader"
  val PodReaderRoleBindingName = "statefulserverless-read-pods"

  val CassandraJournalType = "Cassandra"
  val UnmanagedJournalDeployment = "Unmanaged"

  val UserContainerName = "user-container"
  val UserPortName = "user-port"
  val DefaultUserPort = 8080
  val UserPortEnvVar = "PORT"

  val KnativeRevisionEnvVar = "K_REVISION"
  val KnativeConfigruationEnvVar = "K_CONDFIGURATION"
  val KnativeServiceEnvVar = "K_SERVICE"

  val KubernetesManagedByLabel = "app.kubernetes.io/managed-by"
}
