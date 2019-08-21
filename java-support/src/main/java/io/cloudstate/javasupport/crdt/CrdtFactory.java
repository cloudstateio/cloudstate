package io.cloudstate.javasupport.crdt;

public interface CrdtFactory {
    GCounter newGCounter();
    PNCounter newPNCounter();
    <T> GSet<T> newGSet();
    <T> ORSet<T> newORSet();
    Flag newFlag();
    <T> LWWRegister<T> newLWWRegister(T value);
    <K, V extends Crdt> ORMap<K, V> newORMap();
    Vote newVote();

    default <K, V> LWWRegisterMap<K, V> newLWWRegisterMap() {
        return new LWWRegisterMap<>(newORMap());
    }

    default <K> PNCounterMap<K> newPNCounterMap() {
        return new PNCounterMap<>(newORMap());
    }
}
