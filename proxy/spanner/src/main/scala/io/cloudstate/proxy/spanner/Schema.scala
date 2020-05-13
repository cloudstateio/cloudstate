package io.cloudstate.proxy.spanner

/**
 * The database schema for akka-persistence-spanner.
 * See [[https://github.com/akka/akka-persistence-spanner/blob/master/journal/src/main/scala/akka/persistence/spanner/internal/SpannerJournalInteractions.scala]].
 */
object Schema {

  def createJournalTableDdl(table: String): String =
    s"""|CREATE TABLE $table (
        |  persistence_id STRING(MAX) NOT NULL,
        |  sequence_nr INT64 NOT NULL,
        |  event BYTES(MAX),
        |  ser_id INT64 NOT NULL,
        |  ser_manifest STRING(MAX) NOT NULL,
        |  write_time TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
        |  writer_uuid STRING(MAX) NOT NULL,
        |) PRIMARY KEY (persistence_id, sequence_nr)""".stripMargin

  def createTagsTableDdl(table: String, journalTable: String): String =
    s"""|CREATE TABLE $table (
        |  persistence_id STRING(MAX) NOT NULL,
        |  sequence_nr INT64 NOT NULL,
        |  tag STRING(MAX) NOT NULL,
        |  write_time TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
        |) PRIMARY KEY (persistence_id, sequence_nr, tag),
        |INTERLEAVE IN PARENT $journalTable ON DELETE CASCADE""".stripMargin

  def createTagsIndexDdl(table: String): String = {
    val name = tagsIndexName(table)
    s"""|CREATE INDEX $name
        |ON $table (
        |  tag,
        |  write_time
        |)""".stripMargin
  }

  def createDeletionsTableDdl(table: String): String =
    s"""|CREATE TABLE $table (
        |  persistence_id STRING(MAX) NOT NULL,
        |  deleted_to INT64 NOT NULL,
        |) PRIMARY KEY (persistence_id)""".stripMargin

  def tagsIndexName(table: String): String =
    s"${table}_tag_and_offset"

  def createSnapshotsTableDdl(table: String): String =
    s"""|CREATE TABLE $table (
        |  persistence_id STRING(MAX) NOT NULL,
        |  sequence_nr INT64 NOT NULL,
        |  timestamp TIMESTAMP NOT NULL,
        |  ser_id INT64 NOT NULL,
        |  ser_manifest STRING(MAX) NOT NULL,
        |  snapshot BYTES(MAX)
        |) PRIMARY KEY (persistence_id, sequence_nr)""".stripMargin
}
