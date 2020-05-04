package io.cloudstate.javasupport.impl.crdt

import java.util.Objects

import io.cloudstate.javasupport.crdt.LWWRegister
import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.crdt.{CrdtClock, CrdtDelta, CrdtState, LWWRegisterDelta, LWWRegisterState}
import com.google.protobuf.any.{Any => ScalaPbAny}

private[crdt] final class LWWRegisterImpl[T](anySupport: AnySupport) extends InternalCrdt with LWWRegister[T] {
  override final val name = "LWWRegister"
  private var value: T = _
  private var deltaValue: Option[ScalaPbAny] = None
  private var clock: LWWRegister.Clock = LWWRegister.Clock.DEFAULT
  private var customClockValue: Long = 0

  override def set(value: T, clock: LWWRegister.Clock, customClockValue: Long): T = {
    Objects.requireNonNull(value)
    val old = this.value
    if (this.value != value) {
      deltaValue = Some(anySupport.encodeScala(value))
      this.value = value
    }
    old
  }

  override def get(): T = value

  override def hasDelta: Boolean = deltaValue.isDefined

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(CrdtDelta.Delta.Lwwregister(LWWRegisterDelta(deltaValue, convertClock(clock), customClockValue)))
    } else None

  override def resetDelta(): Unit = {
    deltaValue = None
    clock = LWWRegister.Clock.DEFAULT
    customClockValue = 0
  }

  override def state: CrdtState.State =
    CrdtState.State.Lwwregister(
      LWWRegisterState(Some(anySupport.encodeScala(value)), convertClock(clock), customClockValue)
    )

  override val applyDelta = {
    case CrdtDelta.Delta.Lwwregister(LWWRegisterDelta(Some(any), _, _, _)) =>
      this.value = anySupport.decode(any).asInstanceOf[T]
  }

  override val applyState = {
    case CrdtState.State.Lwwregister(LWWRegisterState(Some(any), _, _, _)) =>
      this.value = anySupport.decode(any).asInstanceOf[T]
  }

  private def convertClock(clock: LWWRegister.Clock): CrdtClock =
    clock match {
      case LWWRegister.Clock.DEFAULT => CrdtClock.DEFAULT
      case LWWRegister.Clock.REVERSE => CrdtClock.REVERSE
      case LWWRegister.Clock.CUSTOM => CrdtClock.CUSTOM
      case LWWRegister.Clock.CUSTOM_AUTO_INCREMENT => CrdtClock.CUSTOM_AUTO_INCREMENT
    }

  override def toString = s"LWWRegister($value)"
}
