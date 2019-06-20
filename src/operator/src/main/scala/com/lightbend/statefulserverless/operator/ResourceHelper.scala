package com.lightbend.statefulserverless.operator

import play.api.libs.json.Format
import skuber.{ObjectMeta, ObjectResource, OwnerReference, ResourceDefinition, Service}
import skuber.api.client.KubernetesClient
import skuber.rbac.{PolicyRule, Role, RoleBinding, RoleRef, Subject}

import scala.concurrent.{ExecutionContext, Future}

class ResourceHelper(client: KubernetesClient)(implicit ec: ExecutionContext) {

  import OperatorConstants._
  import skuber.json.rbac.format._
  import skuber.json.format._

  private val labels = Map(
    KubernetesManagedByLabel -> StatefulServerlessGroup
  )

  def ensureServiceForEssExists(service: EventSourcedService.Resource): Future[Service] = {
    val expectedService = Service(
      metadata = createMetadata(service.name, Some(service)),
    ).setPort(Service.Port(
      name = "grpc",
      port = 80,
      targetPort = Some(Left(KnativeSidecarH2cPort))
    )).withSelector(EventSourcedServiceUidLabel -> service.uid)
    ensureObjectOwnedByUsExists[Service](expectedService, Some(service)) { existing =>
      existing.copy(spec = existing.spec.map(_.copy(
        ports = expectedService.spec.get.ports,
        selector = expectedService.spec.get.selector,
        _type = expectedService.spec.get._type,
        externalName = expectedService.spec.get.externalName,
        sessionAffinity = expectedService.spec.get.sessionAffinity
      )))
    }
  }

  def ensurePodReaderRoleExists(): Future[Role] = {
    val expectedRole = Role(
      metadata = createMetadata(PodReaderRoleName, None),
      rules = List(
        PolicyRule(
          apiGroups = List(""),
          attributeRestrictions = None,
          nonResourceURLs = Nil,
          resources = List("pods"),
          verbs = List("get", "watch", "list"),
          resourceNames = Nil
        )
      )
    )
    ensureRoleExists(expectedRole, None)
  }

  def ensureDeploymentScalerRoleExists(deploymentName: String, owner: ObjectResource): Future[Role] = {
    val name = deploymentScalerRoleName(owner.name)

    val expectedRole = Role(
      metadata = createMetadata(name, Some(owner)),
      rules = List(
        PolicyRule(
          apiGroups = List("apps"),
          attributeRestrictions = None,
          nonResourceURLs = Nil,
          resources = List("deployments"),
          verbs = List("get", "watch", "list"),
          resourceNames = Nil
        ),
        PolicyRule(
          apiGroups = List("apps"),
          attributeRestrictions = None,
          nonResourceURLs = Nil,
          resources = List("deployments/scale"),
          verbs = List("update"),
          resourceNames = List(deploymentName)
        )
      )
    )
    ensureRoleExists(expectedRole, Some(owner))
  }

  private def deploymentScalerRoleName(serviceName: String) = s"$serviceName-$DeploymentScalerRoleName"

  private def ensureRoleExists(expectedRole: Role, owner: Option[ObjectResource]): Future[Role] = {
    ensureObjectOwnedByUsExists[Role](expectedRole, owner) { existing =>
      existing.copy(rules = expectedRole.rules)
    }
  }

  def ensurePodReaderRoleBindingExists(serviceAccountName: String): Future[RoleBinding] = {
    val name = s"$PodReaderRoleBindingName-$serviceAccountName"
    val expectedRoleBinding = RoleBinding(
      metadata = createMetadata(name, None),
      subjects = List(
        Subject(
          apiVersion = None,
          kind = "ServiceAccount",
          name = serviceAccountName,
          namespace = None
        )
      ),
      roleRef = RoleRef(
        apiGroup = "rbac.authorization.k8s.io",
        kind = "Role",
        name = PodReaderRoleName
      )
    )

    ensureRoleBindingExists(expectedRoleBinding, None)
  }

  def ensureDeploymentScalerRoleBindingExists(serviceAccountName: String, owner: ObjectResource): Future[RoleBinding] = {
    val roleName = deploymentScalerRoleName(owner.name)
    val name = s"${owner.name}-$DeploymentScalerRoleBindingName"
    val expectedRoleBinding = RoleBinding(
      metadata = createMetadata(name, Some(owner)),
      subjects = List(
        Subject(
          apiVersion = None,
          kind = "ServiceAccount",
          name = serviceAccountName,
          namespace = None
        )
      ),
      roleRef = RoleRef(
        apiGroup = "rbac.authorization.k8s.io",
        kind = "Role",
        name = roleName
      )
    )
    ensureRoleBindingExists(expectedRoleBinding, Some(owner))
  }

  private def createMetadata(name: String, owner: Option[ObjectResource]) = {
    ObjectMeta(
      name = name,
      labels = labels,
      ownerReferences = owner.map(owner =>
        OwnerReference(
          apiVersion = owner.apiVersion,
          kind = owner.kind,
          name = owner.name,
          uid = owner.uid,
          controller = Some(true),
          blockOwnerDeletion = Some(true)
        )
      ).toList
    )
  }

  private def ensureRoleBindingExists(roleBinding: RoleBinding, owner: Option[ObjectResource]): Future[RoleBinding] = {
    ensureObjectOwnedByUsExists[RoleBinding](roleBinding, owner) { existing =>
      existing.copy(subjects = roleBinding.subjects, roleRef = roleBinding.roleRef)
    }
  }

  /**
    * If an owner is passed in, returns true if that owner is a controller owner reference of the passed in object.
    *
    * If no owner is passed in, returns true if the Kubernetes managed by label is stateful serverless.
    */
  private def isOwnedByUs(obj: ObjectResource, owner: Option[ObjectResource]): Boolean = {
    owner match {
      case Some(owner) =>
        obj.metadata.ownerReferences.find(_.controller.getOrElse(false)).exists(ref =>
          ref.apiVersion.startsWith(owner.apiVersion.takeWhile(_ != '/') + "/")
        )
      case None =>
        obj.metadata.labels.get(KubernetesManagedByLabel).contains(StatefulServerlessGroup)
    }
  }

  private def ensureObjectOwnedByUsExists[O <: ObjectResource: Format: ResourceDefinition](obj: O, owner: Option[ObjectResource])
    (update: O => O): Future[O] = {
    client.getOption[O](obj.name).flatMap {
      case Some(existing) if isOwnedByUs(existing, owner) =>
        // We manage it, check that it's up to date
        val desired = update(existing)
        if (existing == desired) {
          println(s"Found existing managed ${obj.kind} '${obj.name}'")
          Future.successful(existing)
        } else {
          println(s"Found existing managed ${obj.kind} '${obj.name}' but does not match required config, updating...")
          client.update(desired)
        }
      case Some(existing) =>
        println(s"Found existing non managed ${obj.kind} '${existing.name}'")
        Future.successful(existing)
      case None =>
        println(s"Did not find ${obj.kind} '${obj.name}', creating...")
        client.create(obj)
    }
  }

}
