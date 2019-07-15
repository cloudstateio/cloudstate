package io.cloudstate.proxy

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors.FileDescriptor

import scala.collection.JavaConverters._

object FileDescriptorBuilder {

  /**
    * In order to build a FileDescriptor, you need to build and pass its dependencies first. This walks through
    * a FileDescriptorSet, building each descriptor, and building dependencies along the way, caching the results so
    * when it comes to building it next time, the cached result will be used.
    */
  def build(descriptorSet: DescriptorProtos.FileDescriptorSet): Seq[FileDescriptor] = {
    val allProtos = descriptorSet.getFileList.asScala.map { desc =>
      desc.getName -> desc
    }.toMap
    descriptorSet.getFileList.asScala.foldLeft(Map.empty[String, FileDescriptor]) { (alreadyBuilt, desc) =>
      buildDescriptorWithDependencies(desc, allProtos, Nil, alreadyBuilt)
    }.values.toSeq
  }

  private def buildDescriptorWithDependencies(desc: DescriptorProtos.FileDescriptorProto,
    allProtos: Map[String, DescriptorProtos.FileDescriptorProto], beingBuilt: List[String],
    alreadyBuilt: Map[String, FileDescriptor]): Map[String, FileDescriptor] = {

    if (beingBuilt.contains(desc.getName)) {
      // todo - technically we could support circular dependencies by building with allowing unknown dependencies first,
      // then rebuilding once the circular dependencies have been built. Not sure how protoc handles this one.
      throw EntityDiscoveryException(s"Circular dependency detected in entity spec descriptor: [${desc.getName}] -> ${beingBuilt.map(n => s"[$n]").mkString(" -> ")}")
    } else if (alreadyBuilt.contains(desc.getName)) {
      alreadyBuilt
    } else {
      val currentBeingBuilt = desc.getName :: beingBuilt

      // Ensure all dependencies are built
      val alreadyBuiltWithDependencies = desc.getDependencyList.asScala.foldLeft(alreadyBuilt) {
        case (built, dep) if alreadyBuilt.contains(dep) => built
        case (built, dep) if allProtos.contains(dep) =>
          buildDescriptorWithDependencies(allProtos(dep), allProtos, currentBeingBuilt, built)
        // We'll handle the not found case later on
        case (built, _) => built
      }

      val dependencies = desc.getDependencyList.asScala.map {
        case depDesc if alreadyBuiltWithDependencies.contains(depDesc) =>
          alreadyBuiltWithDependencies(depDesc)
        case notFound =>
          throw EntityDiscoveryException(s"Descriptor dependency [$notFound] not found, dependency path: ${currentBeingBuilt.map(n => s"[$n]").mkString(" -> ")}")
      }

      alreadyBuiltWithDependencies + (desc.getName -> FileDescriptor.buildFrom(desc, dependencies.toArray, true))
    }
  }

}
