package io.cloudstate.javasupport.crdt;

import com.google.protobuf.Any;

import java.util.Optional;

public interface CrdtEntityHandler {
    Optional<Any> handleCommand(Any command, CommandContext context);
    Optional<Any> handleStreamedCommand(Any command, StreamedCommandContext<Any> context);
}
