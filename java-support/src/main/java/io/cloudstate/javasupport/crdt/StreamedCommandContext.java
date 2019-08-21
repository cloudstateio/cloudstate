package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public interface StreamedCommandContext extends CommandContext {
    boolean isStreamed();
    void onChange(Function<SubscriptionContext, Optional<Object>> subscriber);
    void onCancel(Consumer<StreamCancelledContext> effect);
}
