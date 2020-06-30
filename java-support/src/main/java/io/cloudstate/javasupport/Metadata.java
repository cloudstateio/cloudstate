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

import io.cloudstate.javasupport.impl.MetadataImpl;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Transport specific metadata.
 *
 * <p>The exact semantics of how metadata is handled depends on the underlying transport. This API
 * exposes case insensitive lookups on metadata, but maintains the original case of the keys as
 * received or inserted. If case matters, the iterator should be used to access elements.
 *
 * <p>Multiple values are also supported per key, if the underlying transport does not support
 * multiple values per key, which value will be used is undefined.
 *
 * <p>Metadata can either have a string or a binary value. If the underlying transport doesn't
 * support one or the other, how those values are handled is undefined - eg, text values may be
 * UTF-8 encoded in binary, or binary values may be Base64 encoded, it depends on the transport.
 *
 * <p>This API maintains the order of entries, but the underlying transport may not.
 *
 * <p>Implementations of this class should be immutable, all update operations should return a copy
 * of the metadata.
 */
public interface Metadata extends Iterable<Metadata.MetadataEntry> {

  /**
   * Get the string value for the given key, if found.
   *
   * <p>If the entry is a binary entry, nothing will be returned.
   *
   * <p>The key lookup is case insensitive. If multiple entries with the same key are present, the
   * first string entry will be returned.
   *
   * @param key The key to lookup.
   * @return The value, if found.
   */
  Optional<String> get(String key);

  /**
   * Get all the string values for a given key.
   *
   * <p>Binary values will be ignored. The key lookup is case insensitive.
   *
   * @param key The key to lookup.
   * @return A list of all the string values for the given key.
   */
  List<String> getAll(String key);

  /**
   * Get the binary value for the given key, if found.
   *
   * <p>If the entry is a string entry, nothing will be returned.
   *
   * <p>The key lookup is case insensitive. If multiple entries with the same key are present, the
   * first binary entry will be returned.
   *
   * @param key The key to lookup.
   * @return The value, if found.
   */
  Optional<ByteBuffer> getBinary(String key);

  /**
   * Get all the binary values for a given key.
   *
   * <p>String values will be ignored. The key lookup is case insensitive.
   *
   * @param key The key to lookup.
   * @return A list of all the binary values for the given key.
   */
  List<ByteBuffer> getBinaryAll(String key);

  /**
   * Check whether this metadata has a entry for the given key.
   *
   * <p>The key lookup will be case insensitive.
   *
   * @param key The key to lookup.
   * @return True if an entry for the given key exists, otherwise false.
   */
  boolean has(String key);

  /**
   * Get all the keys for all the entries.
   *
   * <p>This list may contain duplicate keys if there are multiple entries with the same key.
   *
   * <p>The case of the keys will be the case as passed from the proxy or from other APIs.
   *
   * @return A list of all the keys in this metadata.
   */
  List<String> getAllKeys();

  /**
   * Set the string value for the given key.
   *
   * <p>This will replace any existing entries that case insensitively match the given key.
   *
   * <p>This method does not modify this Metadata object, it returns a copy of this Metadata object
   * with the entry set.
   *
   * @param key The key to set.
   * @param value The value to set.
   * @return A copy of this Metadata object with the entry set.
   */
  Metadata set(String key, String value);

  /**
   * Set the binary value for the given key.
   *
   * <p>This will replace any existing entries that case insensitively match the given key.
   *
   * <p>This method does not modify this Metadata object, it returns a copy of this Metadata object
   * with the entry set.
   *
   * @param key The key to set.
   * @param value The value to set.
   * @return A copy of this Metadata object with the entry set.
   */
  Metadata setBinary(String key, ByteBuffer value);

  /**
   * Add the string value for the given key.
   *
   * <p>This will not replace any existing entries, it will simply append the entry to the end of
   * the list.
   *
   * <p>This method does not modify this Metadata object, it returns a copy of this Metadata object
   * with the entry added.
   *
   * @param key The key to add.
   * @param value The value to add.
   * @return A copy of this Metadata object with the entry added.
   */
  Metadata add(String key, String value);

  /**
   * Add the binary value for the given key.
   *
   * <p>This will not replace any existing entries, it will simply append the entry to the end of
   * the list.
   *
   * <p>This method does not modify this Metadata object, it returns a copy of this Metadata object
   * with the entry added.
   *
   * @param key The key to add.
   * @param value The value to add.
   * @return A copy of this Metadata object with the entry added.
   */
  Metadata addBinary(String key, ByteBuffer value);

  /**
   * Remove all metadata entries with the given key.
   *
   * <p>The key will be matched against entries case insensitively.
   *
   * <p>This method does not modify this Metadata object, it returns a copy of this Metadata object
   * with the entries removed.
   *
   * @param key The key to remove.
   * @return A copy of this Metadata object with the entries removed.
   */
  Metadata remove(String key);

  /**
   * Clear all metadata entries.
   *
   * <p>This method does not modify this Metadata object, it returns an empty Metadata object.
   *
   * @return An empty metadata object.
   */
  Metadata clear();

  /**
   * Whether this metadata is also a CloudEvent.
   *
   * <p>This will return true if all of the required CloudEvent fields are set, that is, the
   * specversion, id, source and type fields.
   *
   * @return True if the CloudEvent required attributes are set in this Metadata.
   */
  boolean isCloudEvent();

  /**
   * Return a CloudEvent representation of this Metadata.
   *
   * <p>Note that the CloudEvent representation will retain any non CloudEvent metadata when
   * converted back to Metadata.
   *
   * @return This Metadata expressed as CloudEvent metadata.
   * @throws IllegalStateException If this metadata is not a CloudEvent, that is, if it doesn't have
   *     any of specversion, id, source or type CloudEvent fields defined.
   */
  CloudEvent asCloudEvent();

  /**
   * Convert this metadata to a CloudEvent, adding the given required CloudEvent fields.
   *
   * <p>Any metadata in this Metadata object will be left intact when asMetadata is called
   *
   * @param id The id of the CloudEvent.
   * @param source The source of the CloudEvent.
   * @param type The type of the CloudEvent.
   * @return This metadata, represented as a CloudEvent with the specified fields.
   */
  CloudEvent asCloudEvent(String id, URI source, String type);

  /** A metadata entry. */
  interface MetadataEntry {
    /**
     * The key for the metadata entry.
     *
     * <p>The key will be in the original case it was inserted or sent as.
     *
     * @return The key.
     */
    String getKey();

    /**
     * The string value for the metadata entry.
     *
     * @return The string value, or null if this entry is not a string Metadata entry.
     */
    String getValue();

    /**
     * The binary value for the metadata entry.
     *
     * @return The binary value, or null if this entry is not a string Metadata entry.
     */
    ByteBuffer getBinaryValue();

    /**
     * Whether this entry is a text entry.
     *
     * @return True if this entry is a text entry.
     */
    boolean isText();

    /**
     * Whether this entry is a binary entry.
     *
     * @return True if this entry is a binary entry.
     */
    boolean isBinary();
  }

  /** An empty Metadata object. */
  Metadata EMPTY = MetadataImpl.Empty();
}
