package io.cloudstate.javasupport.impl.crdt

import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.crdt.CrdtState

private[crdt] object CrdtStateTransformer {

  def create(state: CrdtState, anySupport: AnySupport): InternalCrdt = {
    val crdt = state.state match {
      case CrdtState.State.Gcounter(_) =>
        new GCounterImpl
      case CrdtState.State.Pncounter(_) =>
        new PNCounterImpl
      case CrdtState.State.Gset(_) =>
        new GSetImpl[Any](anySupport)
      case CrdtState.State.Orset(_) =>
        new ORSetImpl[Any](anySupport)
      case CrdtState.State.Flag(_) =>
        new FlagImpl
      case CrdtState.State.Lwwregister(_) =>
        new LWWRegisterImpl[Any](anySupport)
      case CrdtState.State.Ormap(_) =>
        new ORMapImpl[Any, InternalCrdt](anySupport)
      case CrdtState.State.Vote(_) =>
        new VoteImpl
    }
    crdt.applyState(state.state)
    crdt
  }

}
