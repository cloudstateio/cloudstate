package io.cloudstate.javasupport.crdt;

import com.google.protobuf.Any;

public interface CrdtEntityHandler {
    Any handleCommand(Any command, CommandContext context);
    Any handleStreamedCommand(Any command, StreamedCommandContext context);
}
