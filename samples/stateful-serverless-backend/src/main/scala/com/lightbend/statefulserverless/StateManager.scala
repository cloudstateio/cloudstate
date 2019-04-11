package com.lightbend.statefulserverless

import akka.persistence.PersistentActor
import com.lightbend.statefulserverless.grpc.{ Command, EntityClient }

//FIXME IMPLEMENT
final class StateManager(client: EntityClient) extends PersistentActor {
  override final def receiveCommand: PartialFunction[Any, Unit] = ???
  override final def receiveRecover: PartialFunction[Any, Unit] = ???
  override final def persistenceId: String = "StateManager-" + self.path.name // FIXME derive name from actual proxied service?
}