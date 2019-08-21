package io.cloudstate.javasupport.impl.crdt

import io.cloudstate.javasupport.crdt.{Crdt, CrdtFactory, Flag, GCounter, GSet, LWWRegister, ORMap, ORSet, PNCounter, Vote}
import io.cloudstate.javasupport.impl.AnySupport

trait AbstractCrdtFactory extends CrdtFactory {
  protected def anySupport: AnySupport
  protected def newCrdt[C <: Crdt](crdt: C): C

  override def newGCounter(): GCounter = newCrdt(new GCounterImpl)

  override def newPNCounter(): PNCounter = newCrdt(new PNCounterImpl)

  override def newGSet[T](): GSet[T] = newCrdt(new GSetImpl[T](anySupport))

  override def newORSet[T](): ORSet[T] = newCrdt(new ORSetImpl[T](anySupport))

  override def newFlag(): Flag = newCrdt(new FlagImpl)

  override def newLWWRegister[T](value: T): LWWRegister[T] = {
    val register = newCrdt(new LWWRegisterImpl[T](anySupport))
    register.set(value)
    register
  }

  override def newORMap[K, V <: Crdt](): ORMap[K, V] = ???

  override def newVote(): Vote = newCrdt(new VoteImpl)
}
