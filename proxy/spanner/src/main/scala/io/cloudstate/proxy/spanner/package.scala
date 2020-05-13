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

package io.cloudstate.proxy

import java.time.{Duration => JDuration}
import scala.concurrent.duration.{Duration, FiniteDuration}

package object spanner {

  // TODO Remove once on Scala 2.13, because Akka then tranisitively depends on a scala-java8-compat version which has these converters.
  final implicit class JavaDurationOps(val self: JDuration) extends AnyVal {
    def toScala: FiniteDuration =
      Duration.fromNanos(self.toNanos)
  }
}
