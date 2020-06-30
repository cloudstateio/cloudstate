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

package io.cloudstate.javasupport.impl

import java.net.URI
import java.nio.ByteBuffer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util
import java.util.{Objects, Optional}

import com.google.protobuf.ByteString
import io.cloudstate.javasupport.{CloudEvent, Metadata}
import io.cloudstate.protocol.entity.MetadataEntry

import scala.collection.immutable
import scala.compat.java8.OptionConverters._
import scala.collection.JavaConverters._

private[impl] class MetadataImpl(val entries: immutable.Seq[MetadataEntry]) extends Metadata with CloudEvent {

  override def has(key: String): Boolean = entries.exists(_.key.equalsIgnoreCase(key))

  override def get(key: String): Optional[String] =
    entries.collectFirst {
      case MetadataEntry(k, MetadataEntry.Value.StringValue(value), _) if key.equalsIgnoreCase(k) =>
        value
    }.asJava

  override def getAll(key: String): util.List[String] =
    entries.collect {
      case MetadataEntry(k, MetadataEntry.Value.StringValue(value), _) if key.equalsIgnoreCase(k) =>
        value
    }.asJava

  override def getBinary(key: String): Optional[ByteBuffer] =
    entries.collectFirst {
      case MetadataEntry(k, MetadataEntry.Value.BytesValue(value), _) if key.equalsIgnoreCase(k) =>
        value.asReadOnlyByteBuffer()
    }.asJava

  override def getBinaryAll(key: String): util.List[ByteBuffer] =
    entries.collect {
      case MetadataEntry(k, MetadataEntry.Value.BytesValue(value), _) if key.equalsIgnoreCase(k) =>
        value.asReadOnlyByteBuffer()
    }.asJava

  override def getAllKeys: util.List[String] = entries.map(_.key).asJava

  override def set(key: String, value: String): MetadataImpl = {
    Objects.requireNonNull(key, "Key must not be null")
    Objects.requireNonNull(value, "Value must not be null")
    new MetadataImpl(removeKey(key) :+ MetadataEntry(key, MetadataEntry.Value.StringValue(value)))
  }

  override def setBinary(key: String, value: ByteBuffer): Metadata = {
    Objects.requireNonNull(key, "Key must not be null")
    Objects.requireNonNull(value, "Value must not be null")
    new MetadataImpl(removeKey(key) :+ MetadataEntry(key, MetadataEntry.Value.BytesValue(ByteString.copyFrom(value))))
  }

  override def add(key: String, value: String): Metadata = {
    Objects.requireNonNull(key, "Key must not be null")
    Objects.requireNonNull(value, "Value must not be null")
    new MetadataImpl(entries :+ MetadataEntry(key, MetadataEntry.Value.StringValue(value)))
  }

  override def addBinary(key: String, value: ByteBuffer): Metadata = {
    Objects.requireNonNull(key, "Key must not be null")
    Objects.requireNonNull(value, "Value must not be null")
    new MetadataImpl(entries :+ MetadataEntry(key, MetadataEntry.Value.BytesValue(ByteString.copyFrom(value))))
  }

  override def remove(key: String): MetadataImpl = new MetadataImpl(removeKey(key))

  override def clear(): Metadata = MetadataImpl.Empty

  override def iterator(): util.Iterator[Metadata.MetadataEntry] =
    entries.iterator.map { entry =>
      new Metadata.MetadataEntry {
        override def getKey: String = entry.key
        override def getValue: String = entry.value.stringValue.orNull
        override def getBinaryValue: ByteBuffer = entry.value.bytesValue.map(_.asReadOnlyByteBuffer()).orNull
        override def isText: Boolean = entry.value.isStringValue
        override def isBinary: Boolean = entry.value.isBytesValue
      }
    }.asJava

  private def removeKey(key: String) = entries.filterNot(_.key.equalsIgnoreCase(key))

  def isCloudEvent: Boolean = MetadataImpl.CeRequired.forall(h => has(h))

  override def asCloudEvent(): CloudEvent =
    if (!isCloudEvent) {
      throw new IllegalStateException("Metadata is not a CloudEvent!")
    } else this

  override def asCloudEvent(id: String, source: URI, `type`: String): CloudEvent =
    new MetadataImpl(
      entries.filterNot(e => MetadataImpl.CeRequired(e.key)) ++
      Seq(
        MetadataEntry(MetadataImpl.CeSpecversion, MetadataEntry.Value.StringValue(MetadataImpl.CeSpecversionValue)),
        MetadataEntry(MetadataImpl.CeId, MetadataEntry.Value.StringValue(id)),
        MetadataEntry(MetadataImpl.CeSource, MetadataEntry.Value.StringValue(source.toString)),
        MetadataEntry(MetadataImpl.CeType, MetadataEntry.Value.StringValue(`type`))
      )
    )

  private def getRequiredCloudEventField(key: String) =
    entries
      .collectFirst {
        case MetadataEntry(k, MetadataEntry.Value.StringValue(value), _) if key.equalsIgnoreCase(k) =>
          value
      }
      .getOrElse {
        throw new IllegalStateException(s"Metadata is not a CloudEvent because it does not have required field $key")
      }

  override def specversion(): String = getRequiredCloudEventField(MetadataImpl.CeSpecversion)

  override def id(): String = getRequiredCloudEventField(MetadataImpl.CeId)

  override def withId(id: String): CloudEvent = set(MetadataImpl.CeId, id)

  override def source(): URI = URI.create(getRequiredCloudEventField(MetadataImpl.CeSource))

  override def withSource(source: URI): CloudEvent = set(MetadataImpl.CeSource, source.toString)

  override def `type`(): String = getRequiredCloudEventField(MetadataImpl.CeType)

  override def withType(`type`: String): CloudEvent = set(MetadataImpl.CeType, `type`)

  override def datacontenttype(): Optional[String] = get(MetadataImpl.CeDatacontenttype)

  override def withDatacontenttype(datacontenttype: String): CloudEvent =
    set(MetadataImpl.CeDatacontenttype, datacontenttype)

  override def clearDatacontenttype(): CloudEvent = remove(MetadataImpl.CeDatacontenttype)

  override def dataschema(): Optional[URI] = get(MetadataImpl.CeDataschema).map(URI.create(_))

  override def withDataschema(dataschema: URI): CloudEvent = set(MetadataImpl.CeDataschema, dataschema.toString)

  override def clearDataschema(): CloudEvent = remove(MetadataImpl.CeDataschema)

  override def subject(): Optional[String] = get(MetadataImpl.CeSubject)

  override def withSubject(subject: String): CloudEvent = set(MetadataImpl.CeSubject, subject)

  override def clearSubject(): CloudEvent = remove(MetadataImpl.CeSubject)

  override def time(): Optional[ZonedDateTime] = get(MetadataImpl.CeTime).map(ZonedDateTime.parse(_))

  override def withTime(time: ZonedDateTime): CloudEvent =
    set(MetadataImpl.CeTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time))

  override def clearTime(): CloudEvent = remove(MetadataImpl.CeTime)

  override def asMetadata(): Metadata = this
}

object MetadataImpl {
  val CeSpecversion = "ce-specversion"
  val CeSpecversionValue = "1.0"
  val CeId = "ce-id"
  val CeSource = "ce-source"
  val CeType = "ce-type"
  // As per CloudEvent HTTP encoding spec, we use Content-Type to encode this.
  val CeDatacontenttype = "Content-Type"
  val CeDataschema = "ce-dataschema"
  val CeSubject = "ce-subject"
  val CeTime = "ce-time"
  val CeRequired: Set[String] = Set(CeSpecversion, CeId, CeSource, CeType)

  val Empty = new MetadataImpl(Vector.empty)
}
