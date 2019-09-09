package io.cloudstate.javasupport.crdt;

import java.util.*;

/**
 * Convenience wrapper class for {@link ORMap} that uses {@link LWWRegister}'s for values.
 *
 * <p>This is useful as it allows the map to be used more idiomatically, with plain {@link
 * Map#get(Object)} and {@link Map#put(Object, Object)} calls for values.
 *
 * @param <K> The type for keys.
 * @param <V> The type for values.
 */
public final class LWWRegisterMap<K, V> extends AbstractORMapWrapper<K, V, LWWRegister<V>>
    implements Map<K, V> {

  public LWWRegisterMap(ORMap<K, LWWRegister<V>> ormap) {
    super(ormap);
  }

  @Override
  V getCrdtValue(LWWRegister<V> crdt) {
    return crdt.get();
  }

  @Override
  void setCrdtValue(LWWRegister<V> crdt, V value) {
    crdt.set(value);
  }

  @Override
  LWWRegister<V> getOrUpdateCrdt(K key, V value) {
    return ormap.getOrCreate(key, f -> f.newLWWRegister(value));
  }
}
