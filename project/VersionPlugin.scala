import sbt._
import sbt.Keys._
import sbtdynver.DynVerPlugin

/**
 * Version settings — automatically added to all projects.
 */
object VersionPlugin extends AutoPlugin {
  import DynVerPlugin.autoImport._

  override def requires = DynVerPlugin
  override def trigger = allRequirements

  // we need the DynVer build settings as project settings, so we can have per-project tag prefix and versions
  override def projectSettings = DynVerPlugin.buildSettings ++ Seq(
    version := dynverGitDescribeOutput.value.mkVersion(versionFmt, "latest"),
    dynver := version.value
  )

  // make sure the version doesn't change based on time
  def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
    val tagVersion = if (out.hasNoTags()) "0.0.0" else out.ref.dropPrefix
    val dirtySuffix = if (out.isDirty()) "-dev" else ""
    if (out.isCleanAfterTag) tagVersion
    else tagVersion + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
  }
}
