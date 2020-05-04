package io.cloudstate.javasupport.impl.crdt

import java.util
import java.util.Collections

import io.cloudstate.javasupport.crdt.GSet
import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, GSetDelta, GSetState}
import com.google.protobuf.any.{Any => ScalaPbAny}

import scala.collection.JavaConverters._

private[crdt] final class GSetImpl[T](anySupport: AnySupport)
    extends util.AbstractSet[T]
    with InternalCrdt
    with GSet[T] {
  override final val name = "GSet"
  private val value = new util.HashSet[T]()
  private val added = new util.HashSet[ScalaPbAny]()

  override def size(): Int = value.size()

  override def isEmpty: Boolean = super.isEmpty

  override def contains(o: Any): Boolean = value.contains(o)

  override def add(e: T): Boolean =
    if (value.contains(e)) {
      false
    } else {
      added.add(anySupport.encodeScala(e))
      value.add(e)
    }

  override def remove(o: Any): Boolean = throw new UnsupportedOperationException("Cannot remove elements from a GSet")

  override def iterator(): util.Iterator[T] = Collections.unmodifiableSet(value).iterator()

  override def hasDelta: Boolean = !added.isEmpty

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(CrdtDelta.Delta.Gset(GSetDelta(added.asScala.toVector)))
    } else None

  override def resetDelta(): Unit = added.clear()

  override def state: CrdtState.State = CrdtState.State.Gset(GSetState(value.asScala.toSeq.map(anySupport.encodeScala)))

  override val applyDelta = {
    case CrdtDelta.Delta.Gset(GSetDelta(added, _)) =>
      value.addAll(added.map(e => anySupport.decode(e).asInstanceOf[T]).asJava)
  }

  override val applyState = {
    case CrdtState.State.Gset(GSetState(value, _)) =>
      this.value.clear()
      this.value.addAll(value.map(e => anySupport.decode(e).asInstanceOf[T]).asJava)
  }

  override def toString = s"GSet(${value.asScala.mkString(",")})"
}
