package io.cloudstate.javasupport.crdt;

import com.google.protobuf.Any;

import java.util.Optional;

/**
 * Low level interface for handling CRDT commands.
 * <p/>
 * These are instantiated by a {@link CrdtEntityFactory}.
 * <p/>
 * Generally, this should not be used, rather, a {@link CrdtEntity} annotated class should be used.
 */
public interface CrdtEntityHandler {
    /**
     * Handle the given command. During the handling of a command, a CRDT may be created (if not already created) and
     * updated.
     *
     * @param command The command to handle.
     * @param context The context for the command.
     * @return A reply to the command, if any is sent.
     */
    Optional<Any> handleCommand(Any command, CommandContext context);

    /**
     * Handle the given stream command. During the handling of a command, a CRDT may be created (if not already created)
     * and updated.
     *
     * @param command The command to handle.
     * @param context The context for the command.
     * @return A reply to the command, if any is sent.
     */
    Optional<Any> handleStreamedCommand(Any command, StreamedCommandContext<Any> context);
}
