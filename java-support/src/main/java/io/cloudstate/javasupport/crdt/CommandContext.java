package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

import java.util.Optional;

public interface CommandContext extends CrdtFactory, EffectContext, ClientActionContext {
    long commandId();

    String commandName();
    void delete();
}
