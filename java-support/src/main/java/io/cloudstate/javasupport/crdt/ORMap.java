package io.cloudstate.javasupport.crdt;

import java.util.Map;
import java.util.function.Function;

public interface ORMap<K, V extends Crdt> extends Map<K, V> {
    V getOrCreate(K key, Function<CrdtFactory, V> create);
}
