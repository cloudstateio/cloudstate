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
import java.util.Base64

import akka.Done
import akka.stream.Materializer
import io.cloudstate.operator.stores.StatefulStoreSupport
import play.api.libs.json.Json
import skuber.LabelSelector.dsl._
import skuber._
import skuber.api.client.KubernetesClient

import scala.concurrent.{ExecutionContext, Future}
import skuber.apps.v1.DeploymentList
import skuber.apps.v1.Deployment

import scala.util.control.NonFatal

class StatefulStoreOperatorFactory(implicit mat: Materializer, ec: ExecutionContext) extends
  OperatorFactory[StatefulStore.Status, StatefulStore.Resource] {

  import OperatorConstants._
  import StatefulStore.Resource

  override def apply(client: KubernetesClient, config: OperatorConfig): Operator = new StatefulStoreOperator(client)

  class StatefulStoreOperator(client: KubernetesClient) extends Operator {

    private def status(maybeSpec: Option[Resource], status: String, reason: Option[String] = None, message: Option[String] = None) = StatefulStore.Status(
      conditions = Some(List(
        Condition(
          `type` = StatefulStoreConditionType,
          status = status,
          reason = reason,
          message = message,
          lastUpdateTime = Some(ZonedDateTime.now())
        )
      )),
      lastConfig = maybeSpec.map(spec => Base64.getEncoder.encodeToString(Json.toBytes(Json.toJson(spec.spec))))
    )

    override def handleChanged(resource: Resource): Future[StatusUpdate] = {
      val maybeLastConfig = parseLastConfig(resource.status)
      if (maybeLastConfig.contains(resource.spec) &&
        resource.status.exists(_.conditions.exists(_.exists(c => c.`type` == StatefulStoreConditionType && c.status == TrueStatus)))) {
        // Don't do anything if last time we saw it, we successfully validated it, and it hasn't changed since then.
        Future.successful(StatusUpdate.None)
      } else {

        val maybeOldSupport: Option[(StatefulStore.Spec, StatefulStoreSupport)] = for {
          lastConfig <- maybeLastConfig
          oldType <- lastConfig.`type`
          if !resource.spec.`type`.contains(oldType)
          oldSupport <- StatefulStoreSupport.get(oldType)
        } yield (lastConfig, oldSupport)

        val result = for {
          support <- StatefulStoreSupport.get(resource)
          _ <- support.reconcile(resource, client)
          _ <- Validated.future(updateDependents(resource.name))
          _ <- maybeOldSupport.fold(Validated(Done.done())) {
            case (lastConfig, oldSupport) => Validated.future(oldSupport.delete(lastConfig, client))
          }
        } yield Done

        result.fold(
          errors => StatusUpdate.Update(StatefulStore.Status(Some(errors), resource.status.flatMap(_.lastConfig))),
          _ => StatusUpdate.Update(status(Some(resource), TrueStatus))
        )
      }
    }

    override def handleDeleted(resource: Resource): Future[Done] = {
      updateDependents(resource.name)
    }

    private def updateDependents(name: String) = {

      (for {
        deployments <- client.listSelected[DeploymentList](LabelSelector(
          StatefulStoreLabel is name
        ))
        _ <- Future.sequence(deployments.map(deployment => updateServiceForDeployment(deployment)))
      } yield Done).recover {
        case error =>
          println("Error while attempting to update dependent service configuration resource, ignoring")
          error.printStackTrace()
          Done
      }
    }

    private def updateServiceForDeployment(deployment: Deployment): Future[Done] = {

      if (deployment.metadata.labels.contains(RevisionLabel)) {
        for {
          maybeRevision <- deployment.metadata.labels.get(RevisionLabel).map { revisionName =>
            client.getOption[KnativeRevision.Resource](revisionName)
          }.getOrElse(Future.successful(None))
          _ <- maybeRevision match {
            case Some(revision) =>
              val status = revision.status.getOrElse(KnativeRevision.Status(None, Nil, None, None, None))
              client.updateStatus(revision.withStatus(touchKnativeRevisionStatus(status)))
            case None =>
              Future.successful(Done)
          }
        } yield Done
      } else if (deployment.metadata.labels.contains(StatefulServiceLabel)) {
        for {
          maybeStatefulService <- deployment.metadata.labels.get(StatefulServiceLabel).map { serviceName =>
            client.getOption[StatefulService.Resource](serviceName)
          }.getOrElse(Future.successful(None))
          _ <- maybeStatefulService match {
            case Some(service) =>
              val status = service.status.getOrElse(StatefulService.Status(Nil))
              client.updateStatus(service.withStatus(touchStatefulServiceStatus(status)))
            case None =>
              Future.successful(Done)
          }
        } yield Done

      } else {
        // Don't know what deployed it, ignore.
        Future.successful(Done)
      }

    }

    // Here we change the validation to Unknown. It is the responsibility of the revision controller to
    // handle updates to the store, by changing to unknown we let it go in and do the update.
    private def touchKnativeRevisionStatus(status: KnativeRevision.Status): KnativeRevision.Status = {
      val condition = KnativeRevision.Condition(
        `type` = StatefulStoreConditionType,
        status = UnknownStatus,
        reason = Some("StatefulStoreChanged"),
        lastTransitionTime = Some(ZonedDateTime.now())
      )

      val hasExistingCondition = status.conditions.exists(_.`type` == StatefulStoreConditionType)
      val conditions = if (hasExistingCondition) {
        status.conditions.map {
          case c if c.`type` == StatefulStoreConditionType => condition
          case other => other
        }
      } else {
        status.conditions :+ condition
      }
      status.copy(conditions = conditions)
    }

    private def touchStatefulServiceStatus(status: StatefulService.Status): StatefulService.Status = {
      val condition = Condition(
        `type` = StatefulStoreConditionType,
        status = UnknownStatus,
        reason = Some("StatefulStoreChanged"),
        lastTransitionTime = Some(ZonedDateTime.now())
      )

      val hasExistingCondition = status.conditions.exists(_.`type` == StatefulStoreConditionType)
      val conditions = if (hasExistingCondition) {
        status.conditions.map {
          case c if c.`type` == StatefulStoreConditionType => condition
          case other => other
        }
      } else {
        status.conditions :+ condition
      }
      status.copy(conditions = conditions)
    }

    override def statusFromError(error: Throwable, existing: Option[Resource]): StatusUpdate = {
      StatusUpdate.Update(status(existing, UnknownStatus, Some("UnknownOperatorError"), Some(error.getMessage)))
    }

    private def parseLastConfig(maybeStatus: Option[StatefulStore.Status]) = {
      for {
        status <- maybeStatus
        lastConfigJs <- status.lastConfig
        lastConfig <- try {
          Json.parse(Base64.getDecoder.decode(lastConfigJs)).validate[StatefulStore.Spec].asOpt
        } catch {
          case NonFatal(_) => None
        }
      } yield lastConfig
    }
  }

}