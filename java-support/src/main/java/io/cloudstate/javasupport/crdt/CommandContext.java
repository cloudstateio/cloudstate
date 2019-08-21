package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

public interface CommandContext extends CrdtFactory, EffectContext, ClientActionContext {
    long commandId();
}
