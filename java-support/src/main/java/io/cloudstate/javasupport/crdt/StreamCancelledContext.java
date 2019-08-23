package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.EffectContext;

public interface StreamCancelledContext extends CrdtContext, EffectContext {
    long commandId();
}
