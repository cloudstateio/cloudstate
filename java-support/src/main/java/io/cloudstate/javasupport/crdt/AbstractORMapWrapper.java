package io.cloudstate.javasupport.crdt;

import java.util.*;

/**
 * Utility class for helping implement {@link ORMap} based CRDTs.
 */
abstract class AbstractORMapWrapper<K, V, C extends Crdt> extends AbstractMap<K, V> implements Map<K, V> {

    final ORMap<K, C> ormap;

    AbstractORMapWrapper(ORMap<K, C> ormap) {
        this.ormap = ormap;
    }

    abstract V getCrdtValue(C crdt);

    abstract void setCrdtValue(C crdt, V value);

    abstract C getOrUpdateCrdt(K key, V value);

    @Override
    public int size() {
        return ormap.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return ormap.containsKey(key);
    }

    @Override
    public V get(Object key) {
        C crdt = ormap.get(key);
        if (crdt != null) {
            return getCrdtValue(crdt);
        } else {
            return null;
        }
    }

    @Override
    public V put(K key, V value) {
        C existing = ormap.get(key);
        if (existing != null) {
            V old = getCrdtValue(existing);
            setCrdtValue(existing, value);
            return old;
        } else {
            getOrUpdateCrdt(key, value);
            return null;
        }
    }

    @Override
    public V remove(Object key) {
        C old = ormap.remove(key);
        if (old != null) {
            return getCrdtValue(old);
        } else {
            return null;
        }
    }

    @Override
    public void clear() {
        ormap.clear();
    }

    @Override
    public Set<K> keySet() {
        return ormap.keySet();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new EntrySet();
    }

    private final class MapEntry implements Entry<K, V> {
        private final Entry<K, C> entry;

        MapEntry(Entry<K, C> entry) {
            this.entry = entry;
        }

        @Override
        public K getKey() {
            return entry.getKey();
        }

        @Override
        public V getValue() {
            return getCrdtValue(entry.getValue());
        }

        @Override
        public V setValue(V value) {
            V old = getCrdtValue(entry.getValue());
            setCrdtValue(entry.getValue(), value);
            return old;
        }
    }

    private final class EntrySet extends AbstractSet<Entry<K, V>> implements Set<Entry<K, V>> {
        @Override
        public int size() {
            return ormap.size();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new Iterator<Entry<K, V>>() {
                private final Iterator<Entry<K, C>> iter = ormap.entrySet().iterator();
                @Override
                public boolean hasNext() {
                    return iter.hasNext();
                }

                @Override
                public Entry<K, V> next() {
                    return new MapEntry(iter.next());
                }

                @Override
                public void remove() {
                    iter.remove();
                }
            };
        }

        @Override
        public boolean add(Entry<K, V> kvEntry) {
            return !kvEntry.getValue().equals(put(kvEntry.getKey(), kvEntry.getValue()));
        }

        @Override
        public void clear() {
            ormap.clear();
        }
    }
}
