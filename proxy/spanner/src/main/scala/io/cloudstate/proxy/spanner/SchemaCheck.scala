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
import akka.Done
import akka.actor.typed.{Behavior, Scheduler}
import akka.actor.typed.scaladsl.adapter.TypedSchedulerOps
import akka.actor.CoordinatedShutdown
import akka.pattern.after
import com.google.longrunning.{GetOperationRequest, Operation, OperationsClient}
import com.google.spanner.admin.database.v1.{DatabaseAdminClient, UpdateDatabaseDdlRequest}
import io.grpc.{Status, StatusRuntimeException}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/**
 * This actor checks that the database schema for akka-persistence-spanner is created. It therefore
 * tries to create it and maps expected errors due to already exisiting tables to success. It also
 * defines a readiness check.
 */
object SchemaCheck {

  sealed trait Command
  private final case class HandleFailure(cause: Throwable) extends Command
  private final case object HandleSuccess extends Command
  private final case object TryCreateSchema extends Command

  final class ReadinessCheck extends (() => Future[Boolean]) {
    override def apply(): Future[Boolean] =
      ready.future
  }

  final case object CreateSchemaFailure extends CoordinatedShutdown.Reason

  private val ready = Promise[Boolean]()

  def apply(databaseName: String,
            journalTable: String,
            tagsTable: String,
            deletionsTable: String,
            snapshotsTable: String,
            operationAwaitDelay: FiniteDuration,
            operationAwaitMaxDuration: FiniteDuration,
            adminClient: DatabaseAdminClient,
            operationsClient: OperationsClient,
            numberOfRetries: Int,
            retryDelay: FiniteDuration): Behavior[Command] =
    Behaviors.setup { context =>
      import context.executionContext

      val ddl = Schema.ddl(journalTable, tagsTable, deletionsTable, snapshotsTable)

      def behavior(numberOfRetries: Int): Behavior[Command] =
        Behaviors.receiveMessage {
          case TryCreateSchema =>
            val tryCreateSchemaResult =
              tryCreateSchema(databaseName,
                              ddl,
                              operationAwaitDelay,
                              operationAwaitMaxDuration,
                              adminClient,
                              operationsClient,
                              context.system.scheduler)
            context.pipeToSelf(tryCreateSchemaResult) {
              case Failure(cause) => HandleFailure(cause)
              case _ => HandleSuccess
            }
            Behaviors.same

          case HandleFailure(t) if numberOfRetries <= 0 =>
            context.log.error("Cannot create schema!", t)
            CoordinatedShutdown(context.system).run(CreateSchemaFailure)
            Behaviors.stopped

          case HandleFailure(t) =>
            context.log.warn("Scheduling retry to create schema!", t)
            context.scheduleOnce(retryDelay, context.self, TryCreateSchema)
            behavior(numberOfRetries - 1)

          case HandleSuccess =>
            context.log.info("Schema already existed or successfully created")
            ready.success(true)
            adminClient.close()
            operationsClient.close()
            Behaviors.stopped
        }

      context.self ! TryCreateSchema

      behavior(numberOfRetries)
    }

  def tryCreateSchema(databaseName: String,
                      ddl: Seq[String],
                      operationAwaitDelay: FiniteDuration,
                      operationAwaitMaxDuration: FiniteDuration,
                      adminClient: DatabaseAdminClient,
                      operationsClient: OperationsClient,
                      scheduler: Scheduler)(implicit ec: ExecutionContext): Future[Done] = {
    def tryCreate() =
      adminClient
        .updateDatabaseDdl(UpdateDatabaseDdlRequest(databaseName, ddl))
        .transform {
          case Success(o) =>
            Success(o)
          case Failure(t) if alreadyExists(t) =>
            Success(Operation(done = true, result = Operation.Result.Empty))
          case other =>
            other
        }

    def await(operation: Operation): Future[Done] = {
      val deadline = operationAwaitMaxDuration.fromNow
      def loop(o: Operation): Future[Done] =
        if (deadline.hasTimeLeft())
          operationsClient
            .getOperation(GetOperationRequest(o.name))
            .flatMap { o =>
              if (o.done)
                Future.successful(Done)
              else
                after(operationAwaitDelay, scheduler.toClassic)(loop(o))
            } else
          Future.failed(
            new IllegalStateException(s"Operation ${operation.name} not done after $operationAwaitMaxDuration!")
          )
      if (operation.done)
        Future.successful(Done)
      else
        loop(operation)
    }

    tryCreate().flatMap(await)
  }

  private def alreadyExists(t: Throwable) =
    t match {
      case e: StatusRuntimeException =>
        val isFailedPrecondition = e.getStatus.getCode == Status.Code.FAILED_PRECONDITION
        val isDuplicate = e.getStatus.getDescription.contains(s"Duplicate name in schema")
        isFailedPrecondition && isDuplicate

      case _ =>
        false
    }
}
