/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy.eventsourced

import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria}

import scala.concurrent.Future

class InMemSnapshotStore extends SnapshotStore {

  private[this] final var snapshots = Map.empty[String, SelectedSnapshot]

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.successful(
      snapshots
        .get(persistenceId)
        .filter(
          s =>
            s.metadata.sequenceNr >= criteria.minSequenceNr &&
            s.metadata.sequenceNr <= criteria.maxSequenceNr &&
            s.metadata.timestamp >= criteria.minTimestamp &&
            s.metadata.timestamp <= criteria.maxTimestamp
        )
    )

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    snapshots += metadata.persistenceId -> SelectedSnapshot(metadata, snapshot)
    Future.unit
  }

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    snapshots -= metadata.persistenceId
    Future.unit
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    snapshots -= persistenceId
    Future.unit
  }
}
