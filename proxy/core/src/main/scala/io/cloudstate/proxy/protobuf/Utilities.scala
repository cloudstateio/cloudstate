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

package io.cloudstate.proxy.protobuf

import com.google.protobuf.descriptor.{FieldOptions => spbFieldOptions, MethodOptions => spbMethodOptions}
import com.google.protobuf.Descriptors.{FieldDescriptor, MethodDescriptor}

import scala.collection.JavaConverters._

object Types {
  final val AnyTypeUrlHostName = "type.googleapis.com/"
}

object Options {

  /**
   * ScalaPB doesn't do this conversion for us unfortunately.
   * By doing it, we can use EntitykeyProto.entityKey.get() to read the entity key nicely.
   */
  final def convertFieldOptions(field: FieldDescriptor): spbFieldOptions =
    spbFieldOptions
      .fromJavaProto(field.toProto.getOptions)
      .withUnknownFields(scalapb.UnknownFieldSet(field.getOptions.getUnknownFields.asMap.asScala.map {
        case (idx, f) =>
          idx.toInt -> scalapb.UnknownFieldSet.Field(
            varint = f.getVarintList.iterator.asScala.map(_.toLong).toSeq,
            fixed64 = f.getFixed64List.iterator.asScala.map(_.toLong).toSeq,
            fixed32 = f.getFixed32List.iterator.asScala.map(_.toInt).toSeq,
            lengthDelimited = f.getLengthDelimitedList.asScala.toSeq
          )
      }.toMap))

  /**
   * ScalaPB doesn't do this conversion for us unfortunately.
   * By doing it, we can use HttpProto.entityKey.get() to read the entity key nicely.
   */
  final def convertMethodOptions(method: MethodDescriptor): spbMethodOptions =
    spbMethodOptions
      .fromJavaProto(method.toProto.getOptions)
      .withUnknownFields(
        scalapb.UnknownFieldSet(method.getOptions.getUnknownFields.asMap.asScala.map {
          case (idx, f) =>
            idx.toInt -> scalapb.UnknownFieldSet.Field(
              varint = f.getVarintList.iterator.asScala.map(_.toLong).toSeq,
              fixed64 = f.getFixed64List.iterator.asScala.map(_.toLong).toSeq,
              fixed32 = f.getFixed32List.iterator.asScala.map(_.toInt).toSeq,
              lengthDelimited = f.getLengthDelimitedList.asScala.toSeq
            )
        }.toMap)
      )
}
