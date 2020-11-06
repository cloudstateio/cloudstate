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

package io.cloudstate.proxy.valueentity.store

import akka.util.ByteString
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString => ProtobufByteString}
import io.cloudstate.proxy.valueentity.store.RepositoryImpl.EntitySerializer
import org.scalatest.{Matchers, WordSpecLike}

class EntitySerializerSpec extends WordSpecLike with Matchers {

  "Entity Serializer" should {
    import EntitySerializer._

    val entity = ScalaPbAny("p.cloudstate.io/string", ProtobufByteString.copyFromUtf8("state"))

    "serialize entity" in {
      serialize(entity) shouldBe ByteString("p.cloudstate.io/string|state")
    }

    "deserialize state" in {
      deserialize(serialize(entity)) shouldBe entity
    }

    "not deserialize state" in {
      val wrongSerializedEntity = ByteString("p.cloudstate.io/string_state")
      deserialize(wrongSerializedEntity) should not be entity
    }
  }

}
