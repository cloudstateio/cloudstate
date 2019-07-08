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

import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.Base64

import akka.Done
import akka.stream.Materializer
import skuber.LabelSelector.dsl._
import skuber._
import skuber.api.client.KubernetesClient

import scala.concurrent.{ExecutionContext, Future}
import skuber.apps.v1.DeploymentList
import skuber.apps.v1.Deployment

class EventSourcedJournalOperatorFactory(implicit mat: Materializer, ec: ExecutionContext) extends
  OperatorFactory[EventSourcedJournal.Status, EventSourcedJournal.Resource] {

  import OperatorConstants._
  import EventSourcedJournal.Resource

  override def apply(client: KubernetesClient): Operator = new EventSourcedJournalOperator(client)

  class EventSourcedJournalOperator(client: KubernetesClient) extends Operator {

    private def status(spec: Option[Resource], status: String, reason: Option[String] = None, message: Option[String] = None) = EventSourcedJournal.Status(
      conditions = Some(List(
        Condition(
          `type` = JournalConditionType,
          status = status,
          reason = reason,
          message = message,
          lastUpdateTime = Some(ZonedDateTime.now())
        )
      )),
      specHash = spec.map(hashSpec)
    )

    private def hashSpec(spec: Resource) = {
      val md = MessageDigest.getInstance("MD5")
      val hashBytes = md.digest(spec.spec.toString.getBytes("utf-8"))
      Base64.getEncoder.encodeToString(hashBytes)
    }

    private def errorStatus(spec: Option[Resource], reason: String, message: String) =
      status(spec, FalseStatus, Some(reason), Some(message))

    override def handleChanged(resource: Resource): Future[StatusUpdate] = {
      if (resource.status.exists(_.specHash.contains(hashSpec(resource))) &&
        resource.status.exists(_.conditions.exists(_.exists(c => c.`type` == JournalConditionType && c.status == TrueStatus)))) {
        // Don't do anything if last time we saw it, we successfully validated it, and it hasn't changed since then.
        Future.successful(StatusUpdate.None)
      } else {
        val maybeErrorStatus = resource.spec.`type` match {
          case `CassandraJournalType` =>
            resource.spec.deployment match {
              case `UnmanagedJournalDeployment` =>
                (resource.spec.config \ "service").asOpt[String] match {
                  case Some(_) => None
                  case None =>
                    Some(errorStatus(Some(resource), "MissingServiceName", "No service name declared in unmanaged Cassandra journal"))
                }
              case unknown =>
                Some(errorStatus(Some(resource), "UnknownDeploymentType", s"Unknown Cassandra deployment type: $unknown, supported types for Cassandra are: Unmanaged"))
            }
          case unknown =>
            Some(errorStatus(Some(resource), "UnknownJournalType", s"Unknown journal type: $unknown, supported types are: Cassandra"))
        }

        maybeErrorStatus match {
          case Some(error) => Future.successful(StatusUpdate.Update(error))
          case None =>
            updateDependents(resource.name).map(_ => StatusUpdate.Update(status(Some(resource), TrueStatus)))
        }
      }
    }

    override def handleDeleted(resource: Resource): Future[Done] = {
      updateDependents(resource.name)
    }

    private def updateDependents(name: String) = {

      (for {
        deployments <- client.listSelected[DeploymentList](LabelSelector(
          JournalLabel is name
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
      } else if (deployment.metadata.labels.contains(EventSourcedServiceLabel)) {
        for {
          maybeEventSourcedService <- deployment.metadata.labels.get(EventSourcedServiceLabel).map { serviceName =>
            client.getOption[EventSourcedService.Resource](serviceName)
          }.getOrElse(Future.successful(None))
          _ <- maybeEventSourcedService match {
            case Some(service) =>
              val status = service.status.getOrElse(EventSourcedService.Status(Nil))
              client.updateStatus(service.withStatus(touchEventSourcedServiceStatus(status)))
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
    // handle updates to the journal, by changing to unknown we let it go in and do the update.
    private def touchKnativeRevisionStatus(status: KnativeRevision.Status): KnativeRevision.Status = {
      val condition = KnativeRevision.Condition(
        `type` = JournalConditionType,
        status = UnknownStatus,
        reason = Some("JournalChanged"),
        lastTransitionTime = Some(ZonedDateTime.now())
      )

      val hasExistingCondition = status.conditions.exists(_.`type` == JournalConditionType)
      val conditions = if (hasExistingCondition) {
        status.conditions.map {
          case c if c.`type` == JournalConditionType => condition
          case other => other
        }
      } else {
        status.conditions :+ condition
      }
      status.copy(conditions = conditions)
    }

    private def touchEventSourcedServiceStatus(status: EventSourcedService.Status): EventSourcedService.Status = {
      val condition = EventSourcedService.Condition(
        `type` = JournalConditionType,
        status = UnknownStatus,
        reason = Some("JournalChanged"),
        lastTransitionTime = Some(ZonedDateTime.now())
      )

      val hasExistingCondition = status.conditions.exists(_.`type` == JournalConditionType)
      val conditions = if (hasExistingCondition) {
        status.conditions.map {
          case c if c.`type` == JournalConditionType => condition
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
  }

}