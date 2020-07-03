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

package io.cloudstate.proxy.spanner

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedSchedulerOps
import akka.Done
import akka.actor.typed.Behavior
import akka.actor.Scheduler
import com.google.longrunning.{GetOperationRequest, Operation, OperationsClient}
import com.google.spanner.admin.database.v1.{DatabaseAdminClient, UpdateDatabaseDdlRequest}
import io.grpc.{Status, StatusRuntimeException}
import org.slf4j.Logger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import akka.pattern.after
import scala.concurrent.duration.FiniteDuration

/**
 * This actor checks that the database schema for akka-persistence-spanner is created. It therefore
 * tries to create it and maps expected errors due to already exisiting tables to success. It also
 * defines a readiness check.
 */
object SchemaCheck {

  final class ReadinessCheck extends (() => Future[Boolean]) {
    override def apply(): Future[Boolean] =
      ready.future
  }

  def apply(databaseName: String,
            journalTable: String,
            tagsTable: String,
            deletionsTable: String,
            snapshotsTable: String,
            operationAwaitDelay: FiniteDuration,
            operationAwaitMaxDuration: FiniteDuration,
            adminClient: DatabaseAdminClient,
            operationsClient: OperationsClient): Behavior[Try[Done]] =
    Behaviors.setup { context =>
      import context.executionContext

      val scheduler = context.system.scheduler.toClassic
      val self = context.self

      tryCreateSchema(
        databaseName,
        journalTable,
        tagsTable,
        deletionsTable,
        snapshotsTable,
        operationAwaitDelay,
        operationAwaitMaxDuration,
        adminClient,
        operationsClient,
        scheduler
      ).onComplete(self ! _)

      Behaviors.receiveMessage {
        case Failure(t) =>
          context.log.error("Cannot create schema!", t)
          Behaviors.stopped

        case Success(_) =>
          context.log.info("Schema already existed or successfully created")
          ready.success(true)
          adminClient.close()
          operationsClient.close()
          Behaviors.stopped
      }
    }

  private val ready = Promise[Boolean]()

  private def tryCreateSchema(databaseName: String,
                              journalTable: String,
                              tagsTable: String,
                              deletionsTable: String,
                              snapshotTable: String,
                              operationAwaitDelay: FiniteDuration,
                              operationAwaitMaxDuration: FiniteDuration,
                              adminClient: DatabaseAdminClient,
                              operationsClient: OperationsClient,
                              scheduler: Scheduler)(
      implicit ec: ExecutionContext
  ) = {
    def tryCreate(name: String, ddl: String) =
      adminClient
        .updateDatabaseDdl(UpdateDatabaseDdlRequest(databaseName, List(ddl)))
        .transform {
          case Success(o) =>
            Success(o)
          case Failure(t) if alreadyExists(name, t) =>
            Success(Operation(done = true, result = Operation.Result.Empty))
          case Failure(t) =>
            Failure(t)
        }

    Future
      .sequence(
        List(
          tryCreate(journalTable, Schema.createJournalTableDdl(journalTable)),
          tryCreate(tagsTable, Schema.createTagsTableDdl(tagsTable, journalTable)),
          tryCreate(deletionsTable, Schema.createDeletionsTableDdl(deletionsTable)),
          tryCreate(Schema.tagsIndexName(tagsTable), Schema.createTagsIndexDdl(tagsTable)),
          tryCreate(snapshotTable, Schema.createSnapshotsTableDdl(snapshotTable))
        ).map(_.flatMap(await(operationsClient, scheduler, operationAwaitDelay, operationAwaitMaxDuration)))
      )
      .map(_ => Done)
  }

  private def alreadyExists(name: String, t: Throwable) =
    t match {
      case e: StatusRuntimeException =>
        val isFailedPrecondition = e.getStatus.getCode == Status.Code.FAILED_PRECONDITION
        val isDuplicate = e.getStatus.getDescription.contains(s"Duplicate name in schema: $name")
        isFailedPrecondition && isDuplicate

      case _ =>
        false
    }

  private def await(
      operationsClient: OperationsClient,
      scheduler: Scheduler,
      operationAwaitDelay: FiniteDuration,
      operationAwaitMaxDuration: FiniteDuration
  )(operation: Operation)(implicit ec: ExecutionContext): Future[Done] = {
    val deadline = operationAwaitMaxDuration.fromNow

    def loop(o: Operation): Future[Done] =
      if (deadline.hasTimeLeft)
        operationsClient
          .getOperation(GetOperationRequest(o.name))
          .flatMap { o =>
            if (o.done)
              Future.successful(Done)
            else
              after(operationAwaitDelay, scheduler)(loop(o))
          } else
        Future.failed(
          new IllegalStateException(s"Operation ${operation.name} not done after $operationAwaitMaxDuration!")
        )

    if (operation.done)
      Future.successful(Done)
    else
      loop(operation)
  }
}
