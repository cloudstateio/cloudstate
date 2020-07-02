import sbt._
import sbt.Keys._
import bintray.{BintrayKeys, BintrayPlugin}

/**
 * Publishing of JVM artifacts to Bintray.
 */
object PublishPlugin extends AutoPlugin {
  import BintrayKeys._

  override def requires = BintrayPlugin && VersionPlugin
  override def trigger = allRequirements

  override def buildSettings = Seq(
    bintrayOrganization := Some("cloudstateio"),
    bintrayRelease / aggregate := false, // called once in root projects
    bintraySyncMavenCentral / aggregate := false
  )

  override def projectSettings = Seq(
    bintrayRepository := { if (isSnapshot.value) "snapshots" else "releases" },
    bintrayPackage := "cloudstate",
    bintrayReleaseOnPublish := false, // release and sync packages in one go
    pomIncludeRepository := (_ => false)
  )
}

object NoPublish extends AutoPlugin {
  override def projectSettings = Seq(
    publish / skip := true
  )
}
