package io.cloudstate.samples.pingpong;

import io.cloudstate.javasupport.*;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.eventsourced.*;

import io.cloudstate.pingpong.*;

/** An event sourced entity. */
@EventSourcedEntity
public class PingPongEntity {
  private final String entityId;
  private int sentPings;
  private int seenPings;
  private int sentPongs;
  private int seenPongs;

  public PingPongEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @Snapshot
  public Pingpong.PingPongStats snapshot() {
    return Pingpong.PingPongStats.newBuilder()
        .setSeenPongs(seenPongs)
        .setSeenPings(seenPings)
        .setSentPongs(sentPongs)
        .setSentPings(sentPings)
        .build();
  }

  @SnapshotHandler
  public void handleSnapshot(Pingpong.PingPongStats stats) {
    seenPings = stats.getSeenPings();
    seenPongs = stats.getSeenPongs();
    sentPings = stats.getSentPings();
    sentPongs = stats.getSentPongs();
  }

  @EventHandler
  public void pongSent(Pingpong.PongSent pong) {
    sentPongs += 1;
  }

  @EventHandler
  public void pongSent(Pingpong.PingSent ping) {
    sentPings += 1;
  }

  @EventHandler
  public void pongSent(Pingpong.PingSeen ping) {
    seenPings += 1;
  }

  @EventHandler
  public void pongSent(Pingpong.PongSeen pong) {
    seenPongs += 1;
  }

  @CommandHandler
  public Pingpong.PingSent ping(Pingpong.PongSent pong, CommandContext ctx) {
    Pingpong.PingSent sent =
        Pingpong.PingSent.newBuilder()
            .setId(pong.getId())
            .setSequenceNumber(pong.getSequenceNumber() + 1)
            .build();
    ctx.emit(sent);
    return sent;
  }

  @CommandHandler
  public Pingpong.PongSent pong(Pingpong.PingSent ping, CommandContext ctx) {
    Pingpong.PongSent sent =
        Pingpong.PongSent.newBuilder()
            .setId(ping.getId())
            .setSequenceNumber(ping.getSequenceNumber() + 1)
            .build();
    ctx.emit(sent);
    return sent;
  }

  @CommandHandler
  public Empty seenPong(Pingpong.PongSent pong, CommandContext ctx) {
    ctx.emit(
        Pingpong.PingSeen.newBuilder()
            .setId(pong.getId())
            .setSequenceNumber(pong.getSequenceNumber())
            .build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty seenPing(Pingpong.PingSent ping, CommandContext ctx) {
    ctx.emit(
        Pingpong.PingSeen.newBuilder()
            .setId(ping.getId())
            .setSequenceNumber(ping.getSequenceNumber())
            .build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Pingpong.PingPongStats report(Pingpong.GetReport ping, CommandContext ctx) {
    return snapshot();
  }
}
