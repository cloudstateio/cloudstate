package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

interface SubscriptionContext extends CrdtContext, EffectContext, ClientActionContext {
    void endStream();
}
