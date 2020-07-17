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

package io.cloudstate.proxy.crdt

import akka.actor.{AddressFromURIString, ExtendedActorSystem}
import akka.cluster.UniqueAddress
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import com.google.protobuf.UnsafeByteOperations
import io.cloudstate.proxy.crdt.protobufs.{CrdtVote, CrdtVoteEntry}

class CrdtSerializers(override val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {
  override def manifest(o: AnyRef): String = o match {
    case v: Vote => "V"
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case v: Vote =>
      CrdtVote(v.state.toSeq.sortBy(_._1).map {
        case (address, value) =>
          CrdtVoteEntry(address.address.toString, address.longUid, UnsafeByteOperations.unsafeWrap(value.toByteArray))
      }).toByteArray
    case _ => throw new RuntimeException(s"Don't know how to serialize message of type [${o.getClass}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case "V" =>
      Vote(
        CrdtVote
          .parseFrom(bytes)
          .entries
          .map { entry =>
            (UniqueAddress(AddressFromURIString(entry.address), entry.uid), BigInt(entry.value.toByteArray))
          }
          .toMap,
        None
      )
    case _ => throw new RuntimeException(s"Don't know how to deserialize manifest [$manifest]")
  }
}
