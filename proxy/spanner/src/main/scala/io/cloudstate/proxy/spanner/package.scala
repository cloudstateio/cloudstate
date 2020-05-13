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
