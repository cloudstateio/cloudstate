package io.cloudstate.javasupport.crdt;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Context for handling a streamed command.
 * </p>
 * This may be passed to any {@link CommandHandler} annotated element that corresponds to a command whose output is
 * streamed.
 */
public interface StreamedCommandContext<Output> extends CommandContext {
    /**
     * Whether the call is actually streamed.
     * <p/>
     * When a command is handled via the HTTP adapter, the command will not be streamed since the HTTP adapter does not
     * support streaming, and this will return <code>false</code>. In that case, calls to
     * {@link StreamedCommandContext#onChange(Function)} and {@link StreamedCommandContext#onCancel(Consumer)} will
     * fail.
     *
     * @return True if the command is actually streamed.
     */
    boolean isStreamed();

    /**
     * Register an on change callback for this command.
     * <p/>
     * The callback will be invoked any time the CRDT changes. The callback may inspect the CRDT, but any attempt
     * to modify the CRDT will be ignored and the CRDT will crash.
     * <p/>
     * If the callback returns a value, that value will be sent down the stream. Alternatively, the callback may forward
     * messages to other entities via the passed in {@link SubscriptionContext}. The callback may also emit side effects
     * to other entities via that context.
     *
     * @param subscriber The subscriber callback.
     */
    void onChange(Function<SubscriptionContext, Optional<Output>> subscriber);

    /**
     * Register an on cancel callback for this command.
     * <p/>
     * This will be invoked if the client initiates a stream cancel. It will not be invoked if the entity cancels the
     * stream itself via {@link SubscriptionContext#endStream()} from an
     * {@link StreamedCommandContext#onChange(Function)} callback.
     * <p/>
     * An on cancel callback may update the CRDT, and may emit side effects via the passed in
     * {@link StreamCancelledContext}.
     *
     * @param effect The effect to perform when this stream is cancelled.
     */
    void onCancel(Consumer<StreamCancelledContext> effect);
}
