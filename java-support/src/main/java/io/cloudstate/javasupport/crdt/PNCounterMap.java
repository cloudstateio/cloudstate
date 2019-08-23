package io.cloudstate.javasupport.crdt;

import java.util.*;

public final class PNCounterMap<K> extends AbstractORMapWrapper<K, Long, PNCounter> implements Map<K, Long> {

    PNCounterMap(ORMap<K, PNCounter> ormap) {
        super(ormap);
    }

    public long getValue(Object key) {
        PNCounter counter = ormap.get(key);
        if (counter != null) {
            return counter.getValue();
        } else {
            return 0;
        }
    }

    public long increment(Object key, long by) {
        return getOrUpdate(key).increment(by);
    }

    public long decrement(Object key, long by) {
        return getOrUpdate(key).decrement(by);
    }

    @Override
    Long getCrdtValue(PNCounter pnCounter) {
        return pnCounter.getValue();
    }

    @Override
    void setCrdtValue(PNCounter pnCounter, Long value) {
        long old = pnCounter.getValue();
        pnCounter.increment(value - old);
    }

    @Override
    PNCounter getOrUpdateCrdt(K key, Long value) {
        return ormap.getOrCreate(key, CrdtFactory::newPNCounter);
    }

    private PNCounter getOrUpdate(Object key) {
        return ormap.getOrCreate((K) key, CrdtFactory::newPNCounter);
    }
}
