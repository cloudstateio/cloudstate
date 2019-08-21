package io.cloudstate.javasupport.crdt;


public interface LWWRegister<T> extends Crdt {

    default T set(T value) {
        return set(value, Clock.DEFAULT);
    }
    default T set(T value, Clock clock) {
        return set(value, clock, 0);
    }
    T set(T value, Clock clock, long customClockValue);
    T get();

    enum Clock {
        DEFAULT,
        REVERSE,
        CUSTOM,
        CUSTOM_AUTO_INCREMENT
    }
}
