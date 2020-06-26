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

import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import akka.actor.ExtendedActorSystem
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => pbAny}

final class ProtobufAnySerializer(override val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  final override def manifest(o: AnyRef): String = o match {
    case any: pbAny => any.typeUrl
    case _ =>
      throw new IllegalArgumentException(s"$this only supports com.google.protobuf.any.Any, not ${o.getClass.getName}!")
  }

  final override def toBinary(o: AnyRef): Array[Byte] = o match {
    case any: pbAny => any.value.toByteArray
    case _ =>
      throw new IllegalArgumentException(s"$this only supports com.google.protobuf.any.Any, not ${o.getClass.getName}!")
  }

  final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case null =>
      throw new IllegalArgumentException("null manifest detected instead of valid com.google.protobuf.any.Any.typeUrl")
    case typeUrl => pbAny(typeUrl, ByteString.copyFrom(bytes))
  }
}
