package com.lightbend.statefulserverless.operator

object OperatorConstants {
  val OperatorNamespace = "statefulserverless.lightbend.com"
  val EventSourcedLabel = s"$OperatorNamespace/event-sourced"
  val JournalLabel = s"$OperatorNamespace/journal"
  val KubernetesManagedByLabel = "app.kubernetes.io/managed-by"
  val PodReaderRoleName = "statefulserverless-pod-reader"
  val PodReaderRoleBindingName = "statefulserverless-read-pods"
}
