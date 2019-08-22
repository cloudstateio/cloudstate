package io.cloudstate.javasupport.crdt;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public interface StreamedCommandContext<Output> extends CommandContext {
    boolean isStreamed();
    void onChange(Function<SubscriptionContext, Optional<Output>> subscriber);
    void onCancel(Consumer<StreamCancelledContext> effect);
}
