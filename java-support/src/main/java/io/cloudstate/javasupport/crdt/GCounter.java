package io.cloudstate.javasupport.crdt;

public interface GCounter extends Crdt {
    long getValue();
    long increment(long by);
}
