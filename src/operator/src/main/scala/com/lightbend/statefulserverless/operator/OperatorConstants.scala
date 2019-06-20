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
  final val StatefulServerlessGroup = "statefulserverless.lightbend.com"
  final val StatefulServerlessApiVersionNumber = "v1alpha1"
  final val StatefulServerlessApiVersion = s"$StatefulServerlessGroup/$StatefulServerlessApiVersionNumber"
  final val EventSourcedServiceKind = "EventSourcedService"
  final val JournalLabel = s"$StatefulServerlessGroup/journal"

  final val EventSourcedServiceLabel = s"$StatefulServerlessGroup/eventSourcedService"
  final val EventSourcedServiceUidLabel = s"$StatefulServerlessGroup/eventSourcedServiceUID"

  final val KnativeServingGroup = "serving.knative.dev"
  final val KnativeServingVersion = "v1alpha1"
  final val KnativeServingApiVersion = s"$KnativeServingGroup/$KnativeServingVersion"
  final val RevisionKind = "Revision"
  final val RevisionLabel = s"$KnativeServingGroup/${RevisionKind.toLowerCase(Locale.ROOT)}"
  final val ConfigurationLabel = s"$KnativeServingGroup/configuration"
  final val ServiceLabel = s"$KnativeServingGroup/service"
  final val RevisionUidLabel = s"$KnativeServingGroup/revisionUID"
  final val LastPinnedLabel = s"$KnativeServingGroup/lastPinned"
  final val DeployerKind = "Deployer"

  final val ConditionOk = "Ok"
  final val ConditionResourcesAvailable = "ResourcesAvailable"

  final val ConditionResourcesAvailableNotOwned = "NotOwned"

  final val KnativeServingDeployerName = "KnativeServing"
  final val EventSourcedDeployerName = "EventSourced"

  final val JournalConditionType = "JournalValid"

  final val TrueStatus = "True"
  final val FalseStatus = "False"
  final val UnknownStatus = "Unknown"

  final val PodReaderRoleName = "statefulserverless-pod-reader"
  final val PodReaderRoleBindingName = "statefulserverless-read-pods"

  final val DeploymentScalerRoleName = "statefulserverless-deployment-scaler"
  final val DeploymentScalerRoleBindingName = "statefulserverless-scale-deployment"

  final val CassandraJournalType = "Cassandra"
  final val UnmanagedJournalDeployment = "Unmanaged"

  final val UserContainerName = "user-container"
  final val UserPortName = "user-port"
  final val DefaultUserPort = 8080
  final val UserPortEnvVar = "PORT"

  final val KnativeRevisionEnvVar = "K_REVISION"
  final val KnativeConfigruationEnvVar = "K_CONDFIGURATION"
  final val KnativeServiceEnvVar = "K_SERVICE"

  final val KubernetesManagedByLabel = "app.kubernetes.io/managed-by"

  final val ProtocolH2c = "h2c"
  final val KnativeSidecarPortName = "queue-port"
  final val KnativeSidecarHttpPort = 8012
  final val KnativeSidecarH2cPort = 8013

  final val AkkaManagementPort = 8558
  final val AkkaRemotingPort = 2552
  final val MetricsPort = 9090
  final val MetricsPortName = "queue-metrics"
}
