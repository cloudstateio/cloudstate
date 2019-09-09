package io.cloudstate.javasupport.impl.crdt

import io.cloudstate.javasupport.crdt.PNCounter
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, PNCounterDelta, PNCounterState}

private[crdt] final class PNCounterImpl extends InternalCrdt with PNCounter {
  override final val name = "PNCounter"
  private var value: Long = 0
  private var deltaValue: Long = 0

  override def getValue: Long = value

  override def increment(by: Long): Long = {
    deltaValue += by
    value += by
    value
  }

  override def decrement(by: Long): Long = increment(-by)

  override def hasDelta: Boolean = deltaValue != 0

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(CrdtDelta.Delta.Pncounter(PNCounterDelta(deltaValue)))
    } else None

  override def resetDelta(): Unit = deltaValue = 0

  override def state: CrdtState.State = CrdtState.State.Pncounter(PNCounterState(value))

  override val applyDelta = {
    case CrdtDelta.Delta.Pncounter(PNCounterDelta(increment)) =>
      value += increment
  }

  override val applyState = {
    case CrdtState.State.Pncounter(PNCounterState(value)) =>
      this.value = value
  }

  override def toString = s"PNCounter($value)"
}
