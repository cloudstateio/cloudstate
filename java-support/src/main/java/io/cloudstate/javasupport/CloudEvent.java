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

package io.cloudstate.javasupport;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.Optional;

/** CloudEvent representation of Metadata. */
public interface CloudEvent {

  /**
   * The CloudEvent spec version.
   *
   * @return The spec version.
   */
  String specversion();

  /**
   * The id of this CloudEvent.
   *
   * @return The id.
   */
  String id();

  /**
   * Return a new CloudEvent with the given id.
   *
   * @param id The id to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withId(String id);

  /**
   * The source of this CloudEvent.
   *
   * @return The source.
   */
  URI source();

  /**
   * Return a new CloudEvent with the given source.
   *
   * @param source The source to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withSource(URI source);

  /**
   * The type of this CloudEvent.
   *
   * @return The type.
   */
  String type();

  /**
   * Return a new CloudEvent with the given type.
   *
   * @param type The type to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withType(String type);

  /**
   * The data content type of this CloudEvent.
   *
   * @return The data content type, if set.
   */
  Optional<String> datacontenttype();

  /**
   * Return a new CloudEvent with the given data content type.
   *
   * @param datacontenttype The data content type to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withDatacontenttype(String datacontenttype);

  /**
   * Clear the data content type of this CloudEvent, if set.
   *
   * @return A copy of this CloudEvent.
   */
  CloudEvent clearDatacontenttype();

  /**
   * The data schema of this CloudEvent.
   *
   * @return The data schema, if set.
   */
  Optional<URI> dataschema();

  /**
   * Return a new CloudEvent with the given data schema.
   *
   * @param dataschema The data schema to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withDataschema(URI dataschema);

  /**
   * Clear the data schema of this CloudEvent, if set.
   *
   * @return A copy of this CloudEvent.
   */
  CloudEvent clearDataschema();

  /**
   * The subject of this CloudEvent.
   *
   * @return The subject, if set.
   */
  Optional<String> subject();

  /**
   * Return a new CloudEvent with the given subject.
   *
   * @param subject The subject to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withSubject(String subject);

  /**
   * Clear the subject of this CloudEvent, if set.
   *
   * @return A copy of this CloudEvent.
   */
  CloudEvent clearSubject();

  /**
   * The time of this CloudEvent.
   *
   * @return The time, if set.
   */
  Optional<ZonedDateTime> time();

  /**
   * Return a new CloudEvent with the given time.
   *
   * @param time The time to set.
   * @return A copy of this CloudEvent.
   */
  CloudEvent withTime(ZonedDateTime time);

  /**
   * Clear the time of this CloudEvent, if set.
   *
   * @return A copy of this CloudEvent.
   */
  CloudEvent clearTime();

  /**
   * Return this CloudEvent represented as Metadata.
   *
   * <p>If this CloudEvent was created by {{@link Metadata#asCloudEvent()}}, then any non CloudEvent
   * metadata that was present will still be present.
   *
   * @return This CloudEvent expressed as Cloudstate metadata.
   */
  Metadata asMetadata();

  /**
   * Create a CloudEvent.
   *
   * @param id The id of the CloudEvent.
   * @param source The source of the CloudEvent.
   * @param type The type of the CloudEvent.
   * @return The newly created CloudEvent.
   */
  static CloudEvent of(String id, URI source, String type) {
    return Metadata.EMPTY.asCloudEvent(id, source, type);
  }
}
