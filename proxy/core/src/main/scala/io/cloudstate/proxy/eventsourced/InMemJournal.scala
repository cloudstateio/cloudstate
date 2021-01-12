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

// This is in the akka package so that we can look up the inmem journal from the read journal
package akka.persistence.cloudstate

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, ExtendedActorSystem}
import akka.persistence.{AtomicWrite, Persistence, PersistentRepr}
import akka.persistence.journal.{AsyncWriteJournal, Tagged}
import akka.persistence.query.scaladsl.{EventsByTagQuery, ReadJournal}
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, ReadJournalProvider, Sequence}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import akka.util.Timeout
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

object InmemJournal {
  case class EventsByTag(tag: String, fromOffset: Long)
}

private[persistence] class InmemJournal extends AsyncWriteJournal with InmemMessages with ActorLogging {

  import InmemJournal.EventsByTag

  override implicit protected lazy val mat: Materializer = ActorMaterializer()

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    for (w <- messages; p <- w.payload)
      add(p)
    Future.successful(Nil) // all good
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future.successful(highestSequenceNr(persistenceId))

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = {
    val highest = highestSequenceNr(persistenceId)
    if (highest != 0L && max != 0L)
      read(persistenceId, fromSequenceNr, math.min(toSequenceNr, highest), max).foreach(recoveryCallback)
    Future.successful(())
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    val toSeqNr = math.min(toSequenceNr, highestSequenceNr(persistenceId))
    var snr = 1L
    while (snr <= toSeqNr) {
      delete(persistenceId, snr)
      snr += 1
    }
    Future.successful(())
  }

  override def receivePluginInternal: Receive = {
    case EventsByTag(tag, fromOffset) =>
      log.info("Received EventsByTag query for tag {} from offset {}", tag, fromOffset)
      sender() ! eventsByTagQuery(tag, fromOffset)
  }
}

class InmemReadJournal(system: ExtendedActorSystem, config: Config)
    extends EventsByTagQuery
    with ReadJournalProvider
    with ReadJournal
    with akka.persistence.query.javadsl.ReadJournal {

  private def inmemJournal: ActorRef = Persistence(system).journalFor("inmem-journal")
  private implicit val timeout = Timeout(5.seconds)
  import akka.pattern.ask
  import InmemJournal._

  override def scaladslReadJournal(): ReadJournal = this

  override def javadslReadJournal(): akka.persistence.query.javadsl.ReadJournal =
    // todo this is obviously not right
    this

  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    val fromOffset = offset match {
      case Sequence(seq) => seq
      case NoOffset => 0L
      case unsupported =>
        throw new IllegalArgumentException(s"$unsupported is an unsupported offset type for the in memory read journal")
    }

    Source
      .fromFutureSource(
        (inmemJournal ? EventsByTag(tag, fromOffset)).mapTo[Source[EventEnvelope, NotUsed]]
      )
      .mapMaterializedValue(_ => NotUsed)
  }
}

/**
 * INTERNAL API.
 */
trait InmemMessages {
  private var messages = Map.empty[String, Vector[Message]]
  private var allMessages = Vector.empty[Message]
  private var offset: Long = 0

  private val (broadcast, source) = Source
    .actorRef[Message](8, OverflowStrategy.fail)
    .toMat(BroadcastHub.sink)(Keep.both)
    .run()

  protected implicit def mat: Materializer

  def add(p: PersistentRepr): Unit = {
    val message = toMessage(p)

    messages = messages + (messages.get(p.persistenceId) match {
        case Some(ms) => p.persistenceId -> (ms :+ message)
        case None => p.persistenceId -> Vector(message)
      })

    allMessages :+= message
    broadcast ! message
  }

  def update(pid: String, snr: Long)(f: PersistentRepr => PersistentRepr): Unit = {
    messages = messages.get(pid) match {
      case Some(ms) =>
        messages + (pid -> ms.map(sp => if (sp.repr.sequenceNr == snr) sp.copy(repr = f(sp.repr)) else sp))
      case None => messages
    }
    allMessages = allMessages.map { message =>
      if (message.repr.persistenceId == pid && message.repr.sequenceNr == snr) {
        // todo can tags change?
        message.copy(repr = f(message.repr))
      } else {
        message
      }
    }
  }

  def delete(pid: String, snr: Long): Unit = {
    messages = messages.get(pid) match {
      case Some(ms) =>
        messages + (pid -> ms.filterNot(_.repr.sequenceNr == snr))
      case None => messages
    }
    allMessages = allMessages.filterNot { message =>
      message.repr.persistenceId == pid && message.repr.sequenceNr == snr
    }
  }

  def read(pid: String, fromSnr: Long, toSnr: Long, max: Long): immutable.Seq[PersistentRepr] =
    messages.get(pid) match {
      case Some(ms) =>
        ms.view
          .dropWhile(_.repr.sequenceNr < fromSnr)
          .takeWhile(_.repr.sequenceNr <= toSnr)
          .take(safeLongToInt(max))
          .map(_.repr)
          .toSeq
      case None => Nil
    }

  def highestSequenceNr(pid: String): Long = {
    val snro = for {
      ms <- messages.get(pid)
      m <- ms.lastOption
    } yield m.repr.sequenceNr
    snro.getOrElse(0L)
  }

  def eventsByTagQuery(tag: String, fromOffset: Long): Source[EventEnvelope, NotUsed] =
    // Technically, there's a race condition that could result in messages being dropped here, if a new message is
    // persisted after this returns, but before the subscriber to the query materializes this stream, that message
    // will be dropped. But this is only the in memory journal which makes no sense to use in production.
    Source(allMessages)
      .concat(source)
      .dropWhile(_.offset < fromOffset)
      .filter(_.tags(tag))
      .map { message =>
        EventEnvelope(Sequence(message.offset),
                      message.repr.persistenceId,
                      message.repr.sequenceNr,
                      message.repr.payload)
      }

  private def safeLongToInt(l: Long): Int =
    if (Int.MaxValue < l) Int.MaxValue else l.toInt

  private def toMessage(repr: PersistentRepr): Message = {
    offset += 1
    repr.payload match {
      case Tagged(payload, tags) => Message(offset, repr.withPayload(payload), tags)
      case _ => Message(offset, repr, Set.empty)
    }
  }

}

private case class Message(
    offset: Long,
    repr: PersistentRepr,
    tags: Set[String]
)
