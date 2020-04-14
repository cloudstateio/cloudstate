package io.cloudstate.javasupport.crud;

import akka.util.ByteString;
import io.cloudstate.javasupport.eventsourced.EventHandler;
import io.cloudstate.javasupport.eventsourced.Snapshot;
import io.cloudstate.javasupport.eventsourced.SnapshotHandler;
import io.cloudstate.keyvalue.KeyValue.KVEntity;
import io.cloudstate.keyvalue.KeyValue.KVModification;
import io.cloudstate.keyvalue.KeyValue.KVModificationOrBuilder;

import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public final class KeyValue {
  public static final class Key<T> implements Comparable<Key<?>> {
    private final String _name;
    private final Function<ByteString, T> _reader;
    private final Function<T, ByteString> _writer;

    Key(
        final String name,
        final Function<ByteString, T> reader,
        final Function<T, ByteString> writer) {
      this._name = name;
      this._reader = reader;
      this._writer = writer;
    }

    public final String name() {
      return this._name;
    }

    public final Function<ByteString, T> reader() {
      return this._reader;
    }

    public final Function<T, ByteString> writer() {
      return this._writer;
    }

    @Override
    public final boolean equals(final Object that) {
      if (this == that) return true;
      else if (that instanceof Key) return ((Key<T>) that).name().equals(this.name());
      else return false;
    }

    @Override
    public final int hashCode() {
      return 403 + name().hashCode();
    }

    @Override
    public final int compareTo(final Key<?> other) {
      return name().compareTo(other.name());
    }
  }

  public static final <T> Key<T> keyOf(
      final String name,
      final Function<ByteString, T> reader,
      final Function<T, ByteString> writer) {
    return new Key<T>(
        requireNonNull(name, "Key name cannot be null"),
        requireNonNull(reader, "Key reader cannot be null"),
        requireNonNull(writer, "Key writer cannot be null"));
  }

  // represents the persistent state for last changed for the key value entity
  private static final class ChangeMap {
    // updated and removed collections are mutual exclusive meaning a key should be only in one
    // collection.

    // used for persisting all last updated by keys
    private final java.util.Map<String, ByteString> updated = new java.util.TreeMap<>();
    // used for persisting all last removed by keys
    private final java.util.Set<String> removed = new java.util.TreeSet<>();

    private void setUpdatedKey(String key, ByteString value) {
      updated.put(key, value);
      removed.remove(key);
    }

    private void setRemovedKey(String key) {
      removed.add(key);
      updated.remove(key);
    }

    private KVModification kvModification() {
      final KVModification.Builder builder = KVModification.newBuilder();
      updated.forEach(
          (k, v) -> {
            builder.putUpdatedEntries(k, com.google.protobuf.ByteString.copyFrom(v.asByteBuffer()));
          });
      removed.forEach(builder::addRemovedKeys);
      return builder.build();
    }
  }

  public static final class Map {
    /*
      TODO document invariants
    */
    private final java.util.Map<String, ByteString> unparsed;
    private final java.util.Map<Key<?>, Object> updated = new java.util.TreeMap<>();
    private final java.util.Set<String> removed = new java.util.TreeSet<>();
    private final ChangeMap lastChanged = new ChangeMap();

    public Map() {
      this.unparsed = new java.util.TreeMap<>();
    }

    public final <T> Optional<T> get(final Key<T> key) {
      final T value = (T) updated.get(key);
      if (value != null) {
        return Optional.of(value);
      } else {
        final ByteString bytes = unparsed.get(key.name());
        if (bytes != null) {
          final T parsed = key.reader().apply(bytes);
          requireNonNull(parsed, "Key reader not allowed to read `null`");
          updated.put((Key<Object>) key, parsed);
          return Optional.of(parsed);
        } else {
          return Optional.empty();
        }
      }
    }

    public final <T> void set(final Key<T> key, final T value) {
      requireNonNull(key, "Map key must not be null");
      requireNonNull(value, "Map value must not be null");
      updated.put(key, value);
      unparsed.remove(key.name());
      removed.remove(key.name());
    }

    public final <T> boolean remove(final Key<T> key) {
      requireNonNull(key, "Map key must not be null");
      if (!removed.contains(key.name())
          && (updated.remove(key) != null | unparsed.remove(key.name()) != null)) {
        removed.add(key.name());
        return true;
      } else {
        return false;
      }
    }

    final KVEntity toProto() {
      final KVEntity.Builder builder = KVEntity.newBuilder();
      unparsed.forEach(
          (k, v) -> {
            builder.putEntries(k, com.google.protobuf.ByteString.copyFrom(v.asByteBuffer()));
          });
      updated.forEach(
          (k, v) -> {
            // Skip as this item is only a parsed un-changed item, and we already added those in the
            // previous step
            if (!unparsed.containsKey(k.name())) {
              builder.putEntries(
                  k.name(),
                  com.google.protobuf.ByteString.copyFrom(
                      ((Key<Object>) k).writer().apply(v).asByteBuffer()));
            }
          });
      return builder.build();
    }

    public final KVModification toProtoModification() {
      updated.forEach(
          (k, v) -> {
            // Skip those which remain as unparsed, as they have not been changed
            if (!unparsed.containsKey(k.name())) {
              lastChanged.setUpdatedKey(k.name(), ((Key<Object>) k).writer().apply(v));
            }
          });
      removed.forEach(lastChanged::setRemovedKey);
      return lastChanged.kvModification();
    }

    final void resetTo(KVEntity entityState) {
      unparsed.clear();
      updated.clear();
      removed.clear();
      entityState
          .getEntriesMap()
          .forEach(
              (k, v) ->
                  unparsed.put(
                      k,
                      v.isEmpty()
                          ? ByteString.empty()
                          : ByteString.fromArrayUnsafe(v.toByteArray())));
    }

    final void applyModification(KVModificationOrBuilder modification) {
      // Apply new modifications to the base unparsed values
      modification
          .getUpdatedEntriesMap()
          .forEach(
              (k, v) -> {
                unparsed.put(
                    k,
                    v.isEmpty() ? ByteString.empty() : ByteString.fromArrayUnsafe(v.toByteArray()));

                lastChanged.setUpdatedKey(
                    k,
                    v.isEmpty()
                        ? ByteString.empty()
                        : ByteString.fromArrayUnsafe(
                            v.toByteArray())); // restore the persisted last updated keys
              });

      modification
          .getRemovedKeysList()
          .forEach(
              k -> {
                unparsed.remove(k);
                lastChanged.setRemovedKey(k); // restored the persisted last removed keys
              });
    }
  }

  public abstract static class KeyValueEntity {
    private final Map state = new Map();

    protected Map state() {
      return state;
    }

    @Snapshot
    public KVEntity snapshot() {
      return state.toProto();
    }

    @SnapshotHandler
    public void handleSnapshot(final KVEntity entityState) {
      state.resetTo(entityState);
    }

    @EventHandler
    public void kVModification(final KVModification modification) {
      state.applyModification(modification);
    }
  }
}
