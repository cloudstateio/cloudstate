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
package io.cloudstate.proxy

import akka.serialization.BaseSerializer
import akka.actor.ExtendedActorSystem
import com.google.protobuf.any.{Any => pbAny}

final class ProtobufAnySerializer(override val system: ExtendedActorSystem) extends BaseSerializer {
  final override def toBinary(o: AnyRef): Array[Byte] = o match {
    case any: pbAny => any.toByteArray
    case _ =>
      throw new IllegalArgumentException(s"$this only supports com.google.protobuf.any.Any, not ${o.getClass.getName}!")
  }
  final override def includeManifest: Boolean = false
  final override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val any = pbAny.parseFrom(bytes)
    any
  }
}
