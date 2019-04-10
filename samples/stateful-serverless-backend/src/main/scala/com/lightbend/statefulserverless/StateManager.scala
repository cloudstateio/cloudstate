package com.lightbend.statefulserverless

import akka.persistence.PersistentActor
import com.lightbend.statefulserverless.grpc.{ Command, EntityClient }

//FIXME IMPLEMENT
final class StateManager(client: EntityClient) extends PersistentActor {
  def receiveCommand: PartialFunction[Any, Unit] = ???
  def receiveRecover: PartialFunction[Any, Unit] = ???
  def persistenceId: String = ???
}