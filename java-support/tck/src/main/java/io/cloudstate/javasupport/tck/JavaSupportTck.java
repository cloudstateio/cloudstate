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

package io.cloudstate.javasupport.tck;

import io.cloudstate.javasupport.CloudState;
import io.cloudstate.javasupport.PassivationStrategy;
import io.cloudstate.javasupport.crdt.CrdtEntityOptions;
import io.cloudstate.javasupport.entity.EntityOptions;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntityOptions;
import io.cloudstate.javasupport.tck.model.crdt.CrdtConfiguredEntity;
import io.cloudstate.javasupport.tck.model.eventlogeventing.EventLogSubscriber;
import io.cloudstate.javasupport.tck.model.eventsourced.EventSourcedConfiguredEntity;
import io.cloudstate.javasupport.tck.model.valuebased.ValueEntityConfiguredEntity;
import io.cloudstate.javasupport.tck.model.valuebased.ValueEntityTckModelEntity;
import io.cloudstate.javasupport.tck.model.valuebased.ValueEntityTwoEntity;
import io.cloudstate.javasupport.tck.model.action.ActionTckModelBehavior;
import io.cloudstate.javasupport.tck.model.action.ActionTwoBehavior;
import io.cloudstate.javasupport.tck.model.crdt.CrdtTckModelEntity;
import io.cloudstate.javasupport.tck.model.crdt.CrdtTwoEntity;
import io.cloudstate.javasupport.tck.model.eventsourced.EventSourcedTckModelEntity;
import io.cloudstate.javasupport.tck.model.eventsourced.EventSourcedTwoEntity;
import io.cloudstate.tck.model.Action;
import io.cloudstate.tck.model.Crdt;
import io.cloudstate.tck.model.Eventlogeventing;
import io.cloudstate.tck.model.Eventsourced;
import io.cloudstate.tck.model.valueentity.Valueentity;

import java.time.Duration;

public final class JavaSupportTck {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerAction(
            new ActionTckModelBehavior(),
            Action.getDescriptor().findServiceByName("ActionTckModel"),
            Action.getDescriptor())
        .registerAction(
            new ActionTwoBehavior(),
            Action.getDescriptor().findServiceByName("ActionTwo"),
            Action.getDescriptor())
        .registerEntity(
            ValueEntityTckModelEntity.class,
            Valueentity.getDescriptor().findServiceByName("ValueEntityTckModel"),
            Valueentity.getDescriptor())
        .registerEntity(
            ValueEntityTwoEntity.class,
            Valueentity.getDescriptor().findServiceByName("ValueEntityTwo"))
        .registerEntity(
            ValueEntityConfiguredEntity.class,
            Valueentity.getDescriptor().findServiceByName("ValueEntityConfigured"),
            EntityOptions.defaults() // required timeout of 100 millis for TCK tests
                .withPassivationStrategy(PassivationStrategy.timeout(Duration.ofMillis(100))))
        .registerCrdtEntity(
            CrdtTckModelEntity.class,
            Crdt.getDescriptor().findServiceByName("CrdtTckModel"),
            Crdt.getDescriptor())
        .registerCrdtEntity(CrdtTwoEntity.class, Crdt.getDescriptor().findServiceByName("CrdtTwo"))
        .registerCrdtEntity(
            CrdtConfiguredEntity.class,
            Crdt.getDescriptor().findServiceByName("CrdtConfigured"),
            CrdtEntityOptions.defaults() // required timeout of 100 millis for TCK tests
                .withPassivationStrategy(PassivationStrategy.timeout(Duration.ofMillis(100))))
        .registerEventSourcedEntity(
            EventSourcedTckModelEntity.class,
            Eventsourced.getDescriptor().findServiceByName("EventSourcedTckModel"),
            Eventsourced.getDescriptor())
        .registerEventSourcedEntity(
            EventSourcedTwoEntity.class,
            Eventsourced.getDescriptor().findServiceByName("EventSourcedTwo"))
        .registerEventSourcedEntity(
            EventSourcedConfiguredEntity.class,
            Eventsourced.getDescriptor().findServiceByName("EventSourcedConfigured"),
            EventSourcedEntityOptions.defaults() // required timeout of 100 millis for TCK tests
                .withPassivationStrategy(PassivationStrategy.timeout(Duration.ofMillis(100))))
        .registerAction(
            new EventLogSubscriber(),
            Eventlogeventing.getDescriptor().findServiceByName("EventLogSubscriberModel"))
        .registerEventSourcedEntity(
            io.cloudstate.javasupport.tck.model.eventlogeventing.EventSourcedEntityOne.class,
            Eventlogeventing.getDescriptor().findServiceByName("EventSourcedEntityOne"),
            Eventlogeventing.getDescriptor())
        .registerEventSourcedEntity(
            io.cloudstate.javasupport.tck.model.eventlogeventing.EventSourcedEntityTwo.class,
            Eventlogeventing.getDescriptor().findServiceByName("EventSourcedEntityTwo"),
            Eventlogeventing.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
