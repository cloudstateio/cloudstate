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

import java.util.Locale

object OperatorConstants {
  final val CloudStateGroup = "cloudstate.io"
  final val CloudStateApiVersionNumber = "v1alpha1"
  final val CloudStateApiVersion = s"$CloudStateGroup/$CloudStateApiVersionNumber"
  final val StatefulServiceKind = "StatefulService"
  final val StatefulStoreKind = "StatefulStore"
  final val StatefulStoreLabel = s"$CloudStateGroup/statefulStore"

  final val StatefulServiceLabel = s"$CloudStateGroup/statefulService"
  final val StatefulServiceUidLabel = s"$CloudStateGroup/statefulServiceUID"

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
  final val CloudStateDeployerName = "CloudState"

  final val StatefulStoreConditionType = "StatefulStoreValid"

  final val TrueStatus = "True"
  final val FalseStatus = "False"
  final val UnknownStatus = "Unknown"

  final val PodReaderRoleName = "cloudstate-pod-reader"
  final val PodReaderRoleBindingName = "cloudstate-read-pods"

  final val DeploymentScalerRoleName = "cloudstate-deployment-scaler"
  final val DeploymentScalerRoleBindingName = "cloudstate-scale-deployment"

  final val CassandraStatefulStoreType = "Cassandra"
  final val InMemoryStatefulStoreType = "InMemory"
  final val UnmanagedStatefulStoreDeployment = "Unmanaged"

  final val UserContainerName = "user-container"
  final val UserPortName = "user-port"
  final val DefaultUserPort = 8080
  final val UserPortEnvVar = "PORT"

  final val KnativeRevisionEnvVar = "K_REVISION"
  final val KnativeConfigruationEnvVar = "K_CONFIGURATION"
  final val KnativeServiceEnvVar = "K_SERVICE"

  final val KubernetesManagedByLabel = "app.kubernetes.io/managed-by"

  final val ProtocolH2c = "h2c"
  final val KnativeSidecarPortName = "queue-port"
  final val KnativeSidecarHttpPort = 8012
  final val KnativeSidecarH2cPort = 8013

  final val AkkaManagementPort = 8558
  final val AkkaRemotingPort = 2552
  final val MetricsPort = 9090
  final val MetricsPortName = "metrics"
  final val MetricsPortEnvVar = "METRICS_PORT"
}
