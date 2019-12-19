package io.cloudstate.samples.pingpong;

import io.cloudstate.javasupport.CloudState;
import io.cloudstate.pingpong.Pingpong;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerEventSourcedEntity(
            PingPongEntity.class,
            Pingpong.getDescriptor().findServiceByName("PingPongService"),
            Pingpong.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
