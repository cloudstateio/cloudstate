package io.cloudstate.javasupport.impl.crdt

import io.cloudstate.javasupport.crdt.Flag
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, FlagDelta, FlagState}

final class FlagImpl extends InternalCrdt with Flag {
  override final val name = "Flag"
  private var value: Boolean = false
  private var deltaValue: Boolean = false

  override def isEnabled: Boolean = value

  override def enable(): Unit = {
    if (!deltaValue && !value) {
      deltaValue = true
      value = true
    }
  }

  override def hasDelta: Boolean = deltaValue

  override def delta: Option[CrdtDelta.Delta] = if (hasDelta) {
    Some(CrdtDelta.Delta.Flag(FlagDelta(deltaValue)))
  } else None

  override def resetDelta(): Unit = deltaValue = false

  override def state: CrdtState.State = CrdtState.State.Flag(FlagState(value))

  override val applyDelta = {
    case CrdtDelta.Delta.Flag(FlagDelta(value)) =>
      this.value |= value
  }

  override val applyState = {
    case CrdtState.State.Flag(FlagState(value)) =>
      this.value = value
  }

  override def toString = s"Flag($value)"
}
