package io.cloudstate.javasupport.crdt;

public interface CrdtEntityFactory {
    CrdtEntityHandler create(CrdtFactory context);
}
