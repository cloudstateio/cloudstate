package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.EffectContext;

import java.util.function.Consumer;

/**
 * Context for a stream cancelled event.
 * <p/>
 * This is sent to callbacks registered by {@link StreamedCommandContext#onCancel(Consumer)}.
 */
public interface StreamCancelledContext extends CrdtContext, EffectContext {
    /**
     * The id of the command that the stream was for.
     *
     * @return The ID of the command.
     */
    long commandId();
}
