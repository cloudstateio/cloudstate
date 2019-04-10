package com.lightbend.statefulserverless

import akka.persistence.PersistentActor

//FIXME IMPLEMENT
final class StateManager extends PersistentActor {
  def receiveCommand: PartialFunction[Any, Unit] = ???
  def receiveRecover: PartialFunction[Any, Unit] = ???
  def persistenceId: String = ???
}