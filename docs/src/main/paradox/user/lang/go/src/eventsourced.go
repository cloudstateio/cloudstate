//
// Copyright 2019 Lightbend Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package cloudstate

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"reflect"
	"strings"
	"sync"

	"github.com/cloudstateio/go-support/cloudstate/encoding"
	"github.com/cloudstateio/go-support/cloudstate/protocol"
	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes/any"
)

// Entity
type Entity interface {
	CommandHandler
	EventHandler
}

const snapshotEveryDefault = 100

// EventSourcedEntity captures an Entity, its ServiceName and PersistenceID.
// It is used to be registered as an event sourced entity on a CloudState instance.
//#event-sourced-entity-type
type EventSourcedEntity struct {
	// ServiceName is the fully qualified name of the gRPC service that implements this entity's interface.
	// Setting it is mandatory.
	ServiceName string
	// PersistenceID is used to namespace events in the journal, useful for
	// when you share the same database between multiple entities. It defaults to
	// the simple name for the entity type.
	// It’s good practice to select one explicitly, this means your database
	// isn’t dependent on type names in your code.
	// Setting it is mandatory.
	PersistenceID string
	// The snapshotEvery parameter controls how often snapshots are taken,
	// so that the entity doesn't need to be recovered from the whole journal
	// each time it’s loaded. If left unset, it defaults to 100.
	// Setting it to a negative number will result in snapshots never being taken.
	SnapshotEvery int64

//#event-sourced-entity-func
	// EntityFactory is a factory method which generates a new Entity.
	EntityFunc func() Entity
//#event-sourced-entity-func
}
//#event-sourced-entity-type

// init get its Entity type and Zero-Value it to
// something we can use as an initializer.
func (e *EventSourcedEntity) init() error {
	e.SnapshotEvery = snapshotEveryDefault
	return nil
}

// The EntityInstance represents a concrete instance of
// a event sourced entity
type EntityInstance struct {
	// Instance is an instance of the EventSourcedEntity.Entity
	Instance interface{}
	// EventSourcedEntity describes the instance
	EventSourcedEntity *EventSourcedEntity

	eventSequence int64
}

func (e *EntityInstance) shouldSnapshot() bool {
	return e.eventSequence >= e.EventSourcedEntity.SnapshotEvery
}

func (e *EntityInstance) resetSnapshotEvery() {
	e.eventSequence = 0
}

// A EntityInstanceContext represents a event sourced entity together with its
// associated service.
// Commands are dispatched through this context.
type EntityInstanceContext struct { // TODO: EntityInstanceContext might be actually a EntityInstance
	// EntityInstance is the entity instance of this context
	EntityInstance *EntityInstance
	// active indicates if this context is active
	active bool // TODO: inactivate a context in case of errors
}

// ServiceName returns the contexts service name.
func (c EntityInstanceContext) ServiceName() string {
	return c.EntityInstance.EventSourcedEntity.ServiceName
}

// EventSourcedServer is the implementation of the EventSourcedServer server API for EventSourced service.
type EventSourcedServer struct {
	// entities are indexed by their service name
	entities map[string]*EventSourcedEntity
	// contexts are entity instance contexts indexed by their entity ids
	contexts map[string]*EntityInstanceContext
}

// newEventSourcedServer returns an initialized EventSourcedServer
func newEventSourcedServer() *EventSourcedServer {
	return &EventSourcedServer{
		entities: make(map[string]*EventSourcedEntity),
		contexts: make(map[string]*EntityInstanceContext),
	}
}

func (esh *EventSourcedServer) registerEntity(ese *EventSourcedEntity) error {
	if _, exists := esh.entities[ese.ServiceName]; exists {
		return fmt.Errorf("EventSourcedEntity with service name: %s is already registered", ese.ServiceName)
	}
	esh.entities[ese.ServiceName] = ese
	return nil
}

// Handle handles the stream. One stream will be established per active entity.
// Once established, the first message sent will be Init, which contains the entity ID, and,
// if the entity has previously persisted a snapshot, it will contain that snapshot. It will
// then send zero to many event messages, one for each event previously persisted. The entity
// is expected to apply these to its state in a deterministic fashion. Once all the events
// are sent, one to many commands are sent, with new commands being sent as new requests for
// the entity come in. The entity is expected to reply to each command with exactly one reply
// message. The entity should reply in order, and any events that the entity requests to be
// persisted the entity should handle itself, applying them to its own state, as if they had
// arrived as events when the event stream was being replayed on load.
func (esh *EventSourcedServer) Handle(stream protocol.EventSourced_HandleServer) error {
	var entityId string
	var failed error
	for {
		if failed != nil {
			return failed
		}
		msg, recvErr := stream.Recv()
		if recvErr == io.EOF {
			return nil
		}
		if recvErr != nil {
			return recvErr
		}
		if cmd := msg.GetCommand(); cmd != nil {
			if err := esh.handleCommand(cmd, stream); err != nil {
				// TODO: in general, what happens with the stream here if an error happens?
				failed = handleFailure(err, stream, cmd.GetId())
			}
			continue
		}
		if event := msg.GetEvent(); event != nil {
			// TODO spec: Why does command carry the entityId and an event not?
			if err := esh.handleEvent(entityId, event); err != nil {
				failed = handleFailure(err, stream, 0)
			}
			continue
		}
		if init := msg.GetInit(); init != nil {
			if err := esh.handleInit(init); err != nil {
				failed = handleFailure(err, stream, 0)
			}
			entityId = init.GetEntityId()
			continue
		}
	}
}

func (esh *EventSourcedServer) handleInit(init *protocol.EventSourcedInit) error {
	eid := init.GetEntityId()
	if _, present := esh.contexts[eid]; present {
		return NewFailureError("unable to server.Send")
	}
	entity := esh.entities[init.GetServiceName()]
	esh.contexts[eid] = &EntityInstanceContext{
		EntityInstance: &EntityInstance{
			Instance:           entity.EntityFunc(),
			EventSourcedEntity: entity,
		},
		active: true,
	}

	if err := esh.handleInitSnapshot(init); err != nil {
		return NewFailureError("unable to server.Send. %w", err)
	}
	esh.subscribeEvents(esh.contexts[eid].EntityInstance)
	return nil
}

func (esh *EventSourcedServer) handleInitSnapshot(init *protocol.EventSourcedInit) error {
	if init.Snapshot == nil {
		return nil
	}
	entityId := init.GetEntityId()
	if snapshotHandler, ok := esh.contexts[entityId].EntityInstance.Instance.(SnapshotHandler); ok {
		snapshot, err := esh.unmarshalSnapshot(init)
		if snapshot == nil || err != nil {
			return NewFailureError("handling snapshot failed with: %v", err)
		}
		handled, err := snapshotHandler.HandleSnapshot(snapshot)
		if err != nil {
			return NewFailureError("handling snapshot failed with: %v", err)
		}
		if handled {
			esh.contexts[entityId].EntityInstance.eventSequence = init.GetSnapshot().SnapshotSequence
		}
		return nil
	}
	return nil
}

func (EventSourcedServer) unmarshalSnapshot(init *protocol.EventSourcedInit) (interface{}, error) {
	// see: https://developers.google.com/protocol-buffers/docs/reference/csharp/class/google/protobuf/well-known-types/any#typeurl
	typeUrl := init.Snapshot.Snapshot.GetTypeUrl()
	if !strings.Contains(typeUrl, "://") {
		typeUrl = "https://" + typeUrl
	}
	typeURL, err := url.Parse(typeUrl)
	if err != nil {
		return nil, err
	}
	switch typeURL.Host {
	case encoding.PrimitiveTypeURLPrefix:
		snapshot, err := encoding.UnmarshalPrimitive(init.Snapshot.Snapshot)
		if err != nil {
			return nil, fmt.Errorf("unmarshalling snapshot failed with: %v", err)
		}
		return snapshot, nil
	case protoAnyBase:
		msgName := strings.TrimPrefix(init.Snapshot.Snapshot.GetTypeUrl(), protoAnyBase+"/") // TODO: this might be something else than a proto message
		messageType := proto.MessageType(msgName)
		if messageType.Kind() == reflect.Ptr {
			if message, ok := reflect.New(messageType.Elem()).Interface().(proto.Message); ok {
				err := proto.Unmarshal(init.Snapshot.Snapshot.Value, message)
				if err != nil {
					return nil, fmt.Errorf("unmarshalling snapshot failed with: %v", err)
				}
				return message, nil
			}
		}
	}
	return nil, fmt.Errorf("unmarshalling snapshot failed with: no snapshot unmarshaller found for: %v", typeURL.String())
}

func (esh *EventSourcedServer) subscribeEvents(instance *EntityInstance) {
	if emitter, ok := instance.Instance.(EventEmitter); ok {
		emitter.Subscribe(&Subscription{
			OnNext: func(event interface{}) error {
				err := esh.applyEvent(instance, event)
				if err == nil {
					instance.eventSequence++
				}
				return err
			},
			OnErr: func(err error) {
			}, // TODO: investigate what to report to the proxy
		})
	}
}

func (esh *EventSourcedServer) handleEvent(entityId string, event *protocol.EventSourcedEvent) error {
	if entityId == "" {
		return NewFailureError("no entityId was found from a previous init message for event sequence: %v", event.Sequence)
	}
	entityContext := esh.contexts[entityId]
	if entityContext == nil {
		return NewFailureError("no entity with entityId registered: %v", entityId)
	}
	err := esh.handleEvents(entityContext.EntityInstance, event)
	if err != nil {
		return NewFailureError("handle event failed: %v", err)
	}
	return err
}

// handleCommand handles a command received from the Cloudstate proxy.
//
// TODO: remove these following lines of comment
// "Unary RPCs where the client sends a single request to the server and
// gets a single response back, just like a normal function call." are supported right now.
//
// to handle a command we need
// - the entity id, which identifies the entity (its instance) uniquely(?) for this user function instance
// - the service name, like "com.example.shoppingcart.ShoppingCart"
// - a command id
// - a command name, which is one of the gRPC service rpcs defined by this entities service
// - the command payload, which is the message sent for the command as a protobuf.Any blob
// - a streamed flag, (TODO: for what?)
//
// together, these properties allow to call a method of the entities registered service and
// return its response as a reply to the Cloudstate proxy.
//
// Events:
// Beside calling the service method, we have to collect "events" the service might emit.
// These events afterwards have to be handled by a EventHandler to update the state of the
// entity. The Cloudstate proxy can re-play these events at any time
func (esh *EventSourcedServer) handleCommand(cmd *protocol.Command, server protocol.EventSourced_HandleServer) error {
	msgName := strings.TrimPrefix(cmd.Payload.GetTypeUrl(), protoAnyBase+"/")
	messageType := proto.MessageType(msgName)
	if messageType.Kind() != reflect.Ptr {
		return fmt.Errorf("messageType: %s is of non Ptr kind", messageType)
	}
	// get a zero-ed message of this type
	if message, ok := reflect.New(messageType.Elem()).Interface().(proto.Message); ok {
		// and marshal onto it what we got as an any.Any onto it
		err := proto.Unmarshal(cmd.Payload.Value, message)
		if err != nil {
			return fmt.Errorf("%s, %w", err, ErrMarshal)
		} else {
			// we're ready to handle the proto message
			entityContext := esh.contexts[cmd.GetEntityId()]
			if commandHandler, ok := entityContext.EntityInstance.Instance.(CommandHandler); ok {
				// The gRPC implementation returns the rpc return method
				// and an error as a second return value.
				_, reply, errReturned := commandHandler.HandleCommand(server.Context(), message)
				// the error
				if errReturned != nil {
					// TCK says: TODO Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
					return NewProtocolFailure(protocol.Failure{
						CommandId:   cmd.GetId(),
						Description: errReturned.Error(),
					})
				}
				// the reply
				callReply, err := marshalAny(reply)
				if err != nil { // this should never happen
					return NewProtocolFailure(protocol.Failure{
						CommandId:   cmd.GetId(),
						Description: fmt.Errorf("called return value at index 0 is no proto.Message. %w", err).Error(),
					})
				}
				// emitted events
				events, err := marshalEventsAny(entityContext)
				if err != nil {
					return NewProtocolFailure(protocol.Failure{
						CommandId:   cmd.GetId(),
						Description: err.Error(),
					})
				}
				// snapshot
				snapshot, err := esh.handleSnapshots(entityContext)
				if err != nil {
					return NewProtocolFailure(protocol.Failure{
						CommandId:   cmd.GetId(),
						Description: err.Error(),
					})
				}
				return sendEventSourcedReply(&protocol.EventSourcedReply{
					CommandId: cmd.GetId(),
					ClientAction: &protocol.ClientAction{
						Action: &protocol.ClientAction_Reply{
							Reply: &protocol.Reply{
								Payload: callReply,
							},
						},
					},
					Events:   events,
					Snapshot: snapshot,
				}, server)
			}
		}
	}
	return nil
}

func (*EventSourcedServer) handleSnapshots(entityContext *EntityInstanceContext) (*any.Any, error) {
	if !entityContext.EntityInstance.shouldSnapshot() {
		return nil, nil
	}
	if snapshotter, canSnapshot := entityContext.EntityInstance.Instance.(Snapshotter); canSnapshot {
		snap, err := snapshotter.Snapshot()
		if err != nil {
			return nil, fmt.Errorf("getting a snapshot has failed: %v. %w", err, ErrFailure)
		}
		// TODO: we expect a proto.Message but should support other formats
		snapshot, err := marshalAny(snap)
		if err != nil {
			return nil, err
		}
		entityContext.EntityInstance.resetSnapshotEvery()
		return snapshot, nil
	} else {
		// TODO: every entity should implement snapshotting, right?
	}
	return nil, nil
}

// applyEvent applies an event to a local entity
func (esh EventSourcedServer) applyEvent(entityInstance *EntityInstance, event interface{}) error {
	payload, err := marshalAny(event)
	if err != nil {
		return err
	}
	return esh.handleEvents(entityInstance, &protocol.EventSourcedEvent{Payload: payload})
}

func (EventSourcedServer) handleEvents(entityInstance *EntityInstance, events ...*protocol.EventSourcedEvent) error {
	eventHandler, implementsEventHandler := entityInstance.Instance.(EventHandler)
	for _, event := range events {
		// TODO: here's the point where events can be protobufs, serialized as json or other formats
		msgName := strings.TrimPrefix(event.Payload.GetTypeUrl(), protoAnyBase+"/")
		messageType := proto.MessageType(msgName)

		if messageType.Kind() == reflect.Ptr {
			// get a zero-ed message of this type
			if message, ok := reflect.New(messageType.Elem()).Interface().(proto.Message); ok {
				// and marshal onto it what we got as an any.Any onto it
				err := proto.Unmarshal(event.Payload.Value, message)
				if err != nil {
					return fmt.Errorf("%s, %w", err, ErrMarshal)
				} else {
					// we're ready to handle the proto message
					// and we might have a handler
					if implementsEventHandler {
						_, err = eventHandler.HandleEvent(context.Background(), message) // TODO: propagate ctx from callee
						if err != nil {
							return err // FIXME/TODO: is this correct? if we fail here, nothing is safe afterwards.
						}
					}
				}
			}
		} // TODO: what do we do if we haven't handled the events?
	}
	return nil
}
