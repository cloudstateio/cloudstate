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

import io.cloudstate.operator.OperatorConstants.FalseStatus

import scala.concurrent.{ExecutionContext, Future}

/**
 * Probably could do this better with cats
 */
sealed trait Validated[+T] {
  import io.cloudstate.operator.Validated._

  def flatMap[R](f: T => Validated[R])(implicit ec: ExecutionContext): Validated[R] = this match {
    case Valid(t) => f(t)
    case invalid @ Invalid(_) => invalid
    case FutureBased(future) => FutureBased(future.map(_.flatMap(f)))
  }

  def map[R](f: T => R)(implicit ec: ExecutionContext): Validated[R] = this match {
    case Valid(t) => Valid(f(t))
    case invalid @ Invalid(_) => invalid
    case FutureBased(future) => FutureBased(future.map(_.map(f)))
  }

  def toFutureEither(implicit ec: ExecutionContext): Future[Either[List[Condition], T]] =
    fold(Left(_), Right(_))

  def fold[R](invalid: List[Condition] => R, success: T => R)(implicit ec: ExecutionContext): Future[R] = this match {
    case Valid(t) => Future.successful(success(t))
    case Invalid(errors) => Future.successful(invalid(errors))
    case FutureBased(future) => future.flatMap(_.fold(invalid, success))
  }

  def zip[R](other: Validated[R])(implicit ec: ExecutionContext): Validated[(T, R)] = (this, other) match {
    case (Invalid(e1), Invalid(e2)) => Invalid(e1 ++ e2)
    case (invalid @ Invalid(_), _) => invalid
    case (_, invalid @ Invalid(_)) => invalid
    case (FutureBased(future), v) => FutureBased(future.map(_.zip(v)))
    case (v, FutureBased(future)) => FutureBased(future.map(v.zip(_)))
    case (Valid(v1), Valid(v2)) => Valid((v1, v2))
  }

  def filter(predicate: T => Boolean)(implicit ec: ExecutionContext): Validated[T] = this match {
    case v @ Valid(t) if predicate(t) => v
    case Valid(_) =>
      invalid(
        Condition(
          `type` = "Filtered",
          status = OperatorConstants.FalseStatus,
          reason = Some("PredicateFailed"),
          message = Some("A generic predicate failed"),
          lastTransitionTime = Some(ZonedDateTime.now())
        )
      )
    case invalid @ Invalid(_) => invalid
    case FutureBased(future) => FutureBased(future.map(_.filter(predicate)))
  }

  def withFilter(predicate: T => Boolean)(implicit ec: ExecutionContext): Validated[T] = filter(predicate)

}

object Validated {
  def apply[T](t: T): Validated[T] = Valid(t)
  def future[T](future: Future[T])(implicit ec: ExecutionContext): Validated[T] = FutureBased(future.map(Valid(_)))
  def invalid(errors: List[Condition]): Validated[Nothing] = Invalid(errors)
  def invalid(error: Condition): Validated[Nothing] = Invalid(List(error))

  def error(`type`: String, reason: String, message: String) =
    Validated.invalid(
      Condition(`type` = `type`,
                status = FalseStatus,
                severity = Some("Error"),
                lastTransitionTime = Some(ZonedDateTime.now()),
                reason = Some(reason),
                message = Some(message))
    )

  implicit def futureT2Validated[T](future: Future[T])(implicit ec: ExecutionContext): Validated[T] =
    Validated.future(future)
  implicit def futureValidated2Validated[T](future: Future[Validated[T]])(implicit ec: ExecutionContext): Validated[T] =
    FutureBased(future)

  implicit class ValidatedConverters[T](t: T) {
    def valid: Validated[T] = Valid(t)
  }

  private case class FutureBased[+T](future: Future[Validated[T]]) extends Validated[T]
  private case class Valid[+T](t: T) extends Validated[T]
  private case class Invalid(errors: List[Condition]) extends Validated[Nothing]
}
