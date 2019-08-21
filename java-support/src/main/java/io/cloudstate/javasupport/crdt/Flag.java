package io.cloudstate.javasupport.crdt;

public interface Flag extends Crdt {
    boolean isEnabled();
    void enable();
}
