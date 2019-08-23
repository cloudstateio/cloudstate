package io.cloudstate.javasupport.crdt;

import java.util.*;

public final class LWWRegisterMap<K, V> extends AbstractORMapWrapper<K, V, LWWRegister<V>> implements Map<K, V> {

    LWWRegisterMap(ORMap<K, LWWRegister<V>> ormap) {
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
