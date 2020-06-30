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

package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  private[this] final val ignoreClass = Set(
    // FIXME the following are configured in akka-graal-config, remove this when migrating from it
    "akka.io.TcpConnection",
    "akka.io.TcpListener",
    "akka.stream.impl.fusing.ActorGraphInterpreter"
  )

  override final def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    val akkaActorClass =
      access.findClassByName(classOf[akka.actor.Actor].getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names

    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface && !ignoreClass(subtype.getName)) { // TODO investigate whether we should cache the one's we've already added or not?
          println("Automatically registering actor class for reflection purposes: " + subtype.getName)
          RuntimeReflection.register(subtype)
          RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          RuntimeReflection.register(subtype.getDeclaredFields: _*)
        },
      akkaActorClass
    )
  }
}
