package io.cloudstate.javasupport.crdt;

public interface PNCounter extends Crdt {
    long getValue();
    long increment(long by);
    long decrement(long by);
}
