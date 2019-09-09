package io.cloudstate.proxy.autoscaler

import akka.actor.Actor

/**
 * An autoscaler that does nothing.
 */
class NoAutoscaler extends Actor {

  override def receive: Receive = {
    case _: AutoscalerMetrics =>
    // Ignore
  }

}
