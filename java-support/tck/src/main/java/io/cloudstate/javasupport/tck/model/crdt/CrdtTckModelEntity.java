/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.javasupport.tck.model.crdt;

import io.cloudstate.javasupport.ServiceCall;
import io.cloudstate.javasupport.ServiceCallRef;
import io.cloudstate.javasupport.crdt.*;
import io.cloudstate.tck.model.Crdt.*;

import java.util.*;

@CrdtEntity
public class CrdtTckModelEntity {

  private final Crdt crdt;

  private final ServiceCallRef<Request> serviceTwo;

  public CrdtTckModelEntity(CrdtCreationContext context) {
    @SuppressWarnings("unchecked")
    Optional<Crdt> initialised = (Optional<Crdt>) initialCrdt(context.entityId(), context);
    crdt = initialised.orElseGet(() -> createCrdt(context.entityId(), context));
    serviceTwo =
        context
            .serviceCallFactory()
            .lookup("cloudstate.tck.model.crdt.CrdtTwo", "Call", Request.class);
  }

  private static String crdtType(String name) {
    return name.split("-")[0];
  }

  private static Optional<? extends Crdt> initialCrdt(String name, CrdtContext context) {
    String crdtType = crdtType(name);
    switch (crdtType) {
      case "GCounter":
        return context.state(GCounter.class);
      case "PNCounter":
        return context.state(PNCounter.class);
      case "GSet":
        return context.state(GSet.class);
      case "ORSet":
        return context.state(ORSet.class);
      case "LWWRegister":
        return context.state(LWWRegister.class);
      case "Flag":
        return context.state(Flag.class);
      case "ORMap":
        return context.state(ORMap.class);
      case "Vote":
        return context.state(Vote.class);
      default:
        throw new IllegalArgumentException("Unknown CRDT type: " + crdtType);
    }
  }

  private static Crdt createCrdt(String name, CrdtFactory factory) {
    String crdtType = crdtType(name);
    switch (crdtType) {
      case "GCounter":
        return factory.newGCounter();
      case "PNCounter":
        return factory.newPNCounter();
      case "GSet":
        return factory.<String>newGSet();
      case "ORSet":
        return factory.<String>newORSet();
      case "LWWRegister":
        return factory.newLWWRegister("");
      case "Flag":
        return factory.newFlag();
      case "ORMap":
        return factory.<String, Crdt>newORMap();
      case "Vote":
        return factory.newVote();
      default:
        throw new IllegalArgumentException("Unknown CRDT type: " + crdtType);
    }
  }

  @CommandHandler
  public Optional<Response> process(Request request, CommandContext context) {
    boolean forwarding = false;
    for (RequestAction action : request.getActionsList()) {
      switch (action.getActionCase()) {
        case UPDATE:
          applyUpdate(crdt, action.getUpdate());
          switch (action.getUpdate().getWriteConsistency()) {
            case LOCAL:
              context.setWriteConsistency(WriteConsistency.LOCAL);
              break;
            case MAJORITY:
              context.setWriteConsistency(WriteConsistency.MAJORITY);
              break;
            case ALL:
              context.setWriteConsistency(WriteConsistency.ALL);
              break;
          }
          break;
        case DELETE:
          context.delete();
          break;
        case FORWARD:
          forwarding = true;
          context.forward(serviceTwoRequest(action.getForward().getId()));
          break;
        case EFFECT:
          Effect effect = action.getEffect();
          context.effect(serviceTwoRequest(effect.getId()), effect.getSynchronous());
          break;
        case FAIL:
          context.fail(action.getFail().getMessage());
          break;
      }
    }
    return forwarding ? Optional.empty() : Optional.of(responseValue());
  }

  @CommandHandler
  public Optional<Response> processStreamed(
      StreamedRequest request, StreamedCommandContext<Response> context) {
    if (context.isStreamed()) {
      context.onChange(
          subscription -> {
            for (Effect effect : request.getEffectsList())
              subscription.effect(serviceTwoRequest(effect.getId()), effect.getSynchronous());
            if (request.hasEndState() && crdtState(crdt).equals(request.getEndState()))
              subscription.endStream();
            return request.getEmpty() ? Optional.empty() : Optional.of(responseValue());
          });
      if (request.hasCancelUpdate())
        context.onCancel(cancelled -> applyUpdate(crdt, request.getCancelUpdate()));
    }
    if (request.hasInitialUpdate()) applyUpdate(crdt, request.getInitialUpdate());
    return request.getEmpty() ? Optional.empty() : Optional.of(responseValue());
  }

  private void applyUpdate(Crdt crdt, Update update) {
    switch (update.getUpdateCase()) {
      case GCOUNTER:
        ((GCounter) crdt).increment(update.getGcounter().getIncrement());
        break;
      case PNCOUNTER:
        ((PNCounter) crdt).increment(update.getPncounter().getChange());
        break;
      case GSET:
        @SuppressWarnings("unchecked")
        GSet<String> gset = (GSet<String>) crdt;
        gset.add(update.getGset().getAdd());
        break;
      case ORSET:
        @SuppressWarnings("unchecked")
        ORSet<String> orset = (ORSet<String>) crdt;
        switch (update.getOrset().getActionCase()) {
          case ADD:
            orset.add(update.getOrset().getAdd());
            break;
          case REMOVE:
            orset.remove(update.getOrset().getRemove());
            break;
          case CLEAR:
            if (update.getOrset().getClear()) orset.clear();
            break;
        }
        break;
      case LWWREGISTER:
        @SuppressWarnings("unchecked")
        LWWRegister<String> lwwRegister = (LWWRegister<String>) crdt;
        String newValue = update.getLwwregister().getValue();
        if (update.getLwwregister().hasClock()) {
          LWWRegisterClock clock = update.getLwwregister().getClock();
          switch (clock.getClockType()) {
            case DEFAULT:
              lwwRegister.set(newValue);
              break;
            case REVERSE:
              lwwRegister.set(newValue, LWWRegister.Clock.REVERSE, 0);
              break;
            case CUSTOM:
              lwwRegister.set(newValue, LWWRegister.Clock.CUSTOM, clock.getCustomClockValue());
              break;
            case CUSTOM_AUTO_INCREMENT:
              lwwRegister.set(
                  newValue, LWWRegister.Clock.CUSTOM_AUTO_INCREMENT, clock.getCustomClockValue());
              break;
          }
        } else {
          lwwRegister.set(newValue);
        }
        break;
      case FLAG:
        ((Flag) crdt).enable();
        break;
      case ORMAP:
        @SuppressWarnings("unchecked")
        ORMap<String, Crdt> ormap = (ORMap<String, Crdt>) crdt;
        switch (update.getOrmap().getActionCase()) {
          case ADD:
            String addKey = update.getOrmap().getAdd();
            ormap.getOrCreate(addKey, factory -> createCrdt(addKey, factory));
            break;
          case UPDATE:
            String updateKey = update.getOrmap().getUpdate().getKey();
            Update entryUpdate = update.getOrmap().getUpdate().getUpdate();
            Crdt crdtValue =
                ormap.getOrCreate(updateKey, factory -> createCrdt(updateKey, factory));
            applyUpdate(crdtValue, entryUpdate);
            break;
          case REMOVE:
            String removeKey = update.getOrmap().getRemove();
            ormap.remove(removeKey);
            break;
          case CLEAR:
            ormap.clear();
            break;
        }
        break;
      case VOTE:
        ((Vote) crdt).vote(update.getVote().getSelfVote());
        break;
    }
  }

  private Response responseValue() {
    return Response.newBuilder().setState(crdtState(crdt)).build();
  }

  private State crdtState(Crdt crdt) {
    State.Builder builder = State.newBuilder();
    if (crdt instanceof GCounter) {
      GCounter gcounter = (GCounter) crdt;
      builder.setGcounter(GCounterValue.newBuilder().setValue(gcounter.getValue()));
    } else if (crdt instanceof PNCounter) {
      PNCounter pncounter = (PNCounter) crdt;
      builder.setPncounter(PNCounterValue.newBuilder().setValue(pncounter.getValue()));
    } else if (crdt instanceof GSet) {
      @SuppressWarnings("unchecked")
      GSet<String> gset = (GSet<String>) crdt;
      List<String> elements = new ArrayList<>(gset);
      Collections.sort(elements);
      builder.setGset(GSetValue.newBuilder().addAllElements(elements));
    } else if (crdt instanceof ORSet) {
      @SuppressWarnings("unchecked")
      ORSet<String> orset = (ORSet<String>) crdt;
      List<String> elements = new ArrayList<>(orset);
      Collections.sort(elements);
      builder.setOrset(ORSetValue.newBuilder().addAllElements(elements));
    } else if (crdt instanceof LWWRegister) {
      @SuppressWarnings("unchecked")
      LWWRegister<String> lwwRegister = (LWWRegister<String>) crdt;
      builder.setLwwregister(LWWRegisterValue.newBuilder().setValue(lwwRegister.get()));
    } else if (crdt instanceof Flag) {
      builder.setFlag(FlagValue.newBuilder().setValue(((Flag) crdt).isEnabled()));
    } else if (crdt instanceof ORMap) {
      @SuppressWarnings("unchecked")
      ORMap<String, Crdt> ormap = (ORMap<String, Crdt>) crdt;
      List<ORMapEntryValue> entries = new ArrayList<>();
      for (Map.Entry<String, Crdt> entry : ormap.entrySet()) {
        entries.add(
            ORMapEntryValue.newBuilder()
                .setKey(entry.getKey())
                .setValue(crdtState(entry.getValue()))
                .build());
      }
      entries.sort(Comparator.comparing(ORMapEntryValue::getKey));
      builder.setOrmap(ORMapValue.newBuilder().addAllEntries(entries));
    } else if (crdt instanceof Vote) {
      Vote vote = (Vote) crdt;
      builder.setVote(
          VoteValue.newBuilder()
              .setSelfVote(vote.getSelfVote())
              .setVotesFor(vote.getVotesFor())
              .setTotalVoters(vote.getVoters()));
    }
    return builder.build();
  }

  private ServiceCall serviceTwoRequest(String id) {
    return serviceTwo.createCall(Request.newBuilder().setId(id).build());
  }
}
