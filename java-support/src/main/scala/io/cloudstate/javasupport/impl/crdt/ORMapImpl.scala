package io.cloudstate.javasupport.impl.crdt

import java.util
import java.util.{Map, function}

import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.crdt.{Crdt, CrdtFactory, ORMap}
import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, ORSetDelta, ORSetState}

import scala.collection.JavaConverters._

class ORMapImpl[K, V <: Crdt](anySupport: AnySupport) extends util.AbstractMap[K, V] with InternalCrdt with ORMap[K, V] {
  override final val name = "ORMap"
  private val value = new util.HashMap[K, V]()
  private val added = new util.HashMap[K, (ScalaPbAny, V)]()
  private val removed = new util.HashSet[ScalaPbAny]()
  private var cleared = false

  override def getOrCreate(key: K, create: function.Function[CrdtFactory, V]): V = {
    if (value.containsKey(key)) {
      value.get(key)
    } else {
      val encodedKey = anySupport.encodeScala(key)
      val crdt = create(new AbstractCrdtFactory {
        override protected def anySupport: AnySupport = ORMapImpl.this.anySupport
        override protected def newCrdt[C <: Crdt](crdt: C): C = crdt
      })
      value.put(key, crdt)
      added.put(key, (encodedKey, crdt))
      crdt
    }
  }

  override def containsKey(key: Any): Boolean = value.containsKey(key)

  override def get(key: Any): V = value.get(key)

  override def put(key: K, value: V): V = throw new UnsupportedOperationException("Cannot put on an ORMap, use getOrCreate instead.")

  override def remove(key: Any): V = {
    if (value.containsKey(key)) {
      val encodedKey = anySupport.encodeScala(key)
      if (added.containsKey(key)) {
        added.remove(key)
      } else {
        removed.add(encodedKey)
      }
    }
    value.remove(key)
  }

  override def entrySet(): util.Set[util.Map.Entry[K, V]] = new util.AbstractSet {
    override def size(): Int = value.size()
    override def iterator(): util.Iterator[util.Map.Entry[K, V]] = new util.Iterator[util.Map.Entry[K, V]] {
      private val iter = value.entrySet().iterator()
      private var lastNext: util.Map.Entry[K, V] = _
      override def hasNext: Boolean = iter.hasNext
      override def next(): Map.Entry[K, V] = {
        lastNext = iter.next()
        new util.Map.Entry[K, V] {
          private val entry = lastNext
          override def getKey: K = entry.getKey
          override def getValue: V = entry.getValue
          override def setValue(value: V): V = throw new UnsupportedOperationException()
        }
      }
      override def remove(): Unit = {
        if (lastNext != null) {
          val encodedKey = anySupport.encodeScala(lastNext.getKey)
          if (added.containsKey(lastNext.getKey)) {
            added.remove(lastNext.getKey)
          } else {
            removed.add(encodedKey)
          }
        }
        iter.remove()
      }
    }
    override def clear(): Unit = ORMapImpl.this.clear()
  }

  override def size(): Int = value.size()

  override def isEmpty: Boolean = super.isEmpty

  override def clear(): Unit = {
    value.clear()
    cleared = true
    removed.clear()
    added.clear()
  }

  override def hasDelta: Boolean = cleared || !added.isEmpty || !removed.isEmpty

  override def delta: Option[CrdtDelta.Delta] = if (hasDelta) {
    Some(CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed = removed.asScala.toVector, added = added.asScala.toVector)))
  } else None

  override def resetDelta(): Unit = {
    cleared = false
    added.clear()
    removed.clear()
  }

  override def state: CrdtState.State = CrdtState.State.Orset(ORSetState(value.asScala.toSeq.map(anySupport.encodeScala)))

  override val applyDelta = {
    case CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed, added)) =>
      if (cleared) {
        value.clear()
      }
      value.removeAll(removed.map(anySupport.decode).asJava)
      value.addAll(added.map(anySupport.decode).asJava)
  }

  override val applyState = {
    case CrdtState.State.Orset(ORSetState(value)) =>
      this.value.clear()
      this.value.addAll(value.map(anySupport.decode).asJava)
  }

  // todo this needs to be prettier
  override def toString = s"ORMap(${value.asScala.mkString(",")})"
}
