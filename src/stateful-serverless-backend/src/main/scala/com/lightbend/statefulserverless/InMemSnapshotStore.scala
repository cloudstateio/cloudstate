package com.lightbend.statefulserverless

import akka.persistence.{SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria}
import akka.persistence.snapshot.SnapshotStore

import scala.concurrent.Future

class InMemSnapshotStore extends SnapshotStore {

  private var snapshots = Map.empty[String, SelectedSnapshot]

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    Future.successful(snapshots.get(persistenceId)
      .filter(s => s.metadata.sequenceNr >= criteria.minSequenceNr &&
          s.metadata.sequenceNr <= criteria.maxSequenceNr &&
          s.metadata.timestamp >= criteria.minTimestamp &&
          s.metadata.timestamp <= criteria.maxTimestamp
      )
    )
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    snapshots += metadata.persistenceId -> SelectedSnapshot(metadata, snapshot)
    Future.successful(())
  }

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    snapshots -= metadata.persistenceId
    Future.successful(())
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    snapshots -= persistenceId
    Future.successful(())
  }
}
