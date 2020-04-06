package io.cloudstate.proxy.eventing

import akka.Done
import akka.persistence.query.{NoOffset, Offset}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

trait OffsetTracking {

  def acknowledge(consumerId: String, sourceId: String, offset: Offset): Future[Done]

  def loadOffset(consumerId: String, sourceId: String): Future[Offset]

}

class InMemoryOffsetTracking extends OffsetTracking {
  private val trackingMap = TrieMap.empty[(String, String), Offset]

  override def acknowledge(consumerId: String, sourceId: String, offset: Offset): Future[Done] = {
    trackingMap.put((consumerId, sourceId), offset)
    Future.successful(Done)
  }

  override def loadOffset(consumerId: String, sourceId: String): Future[Offset] =
    Future.successful(trackingMap.getOrElse((consumerId, sourceId), NoOffset))
}
