package io.cloudstate

import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess

import scala.collection.JavaConverters._

package object graaltools {
  def reflect[A](x: => A): Option[A] =
    try Option(x)
    catch { case _: ReflectiveOperationException => None }

  implicit class FeatureAccessOps(private val access: QueryReachabilityAccess) extends AnyVal {
    // The "lookup" in "lookupClass"/"lookupSubtype" is meant to carry the "reachable" aspect.

    def lookupClass(className: String): Option[Class[_]] = {
      val cls = access.findClassByName(className)
      if (cls != null && access.isReachable(cls)) Some(cls) else None
    }

    def lookupSubtypes(baseClass: Class[_]): Iterator[Class[_]] =
      access.reachableSubtypes(baseClass).iterator.asScala.filter(_ != null)
  }
}
