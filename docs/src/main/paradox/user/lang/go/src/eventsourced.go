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
	"errors"
	"fmt"
	"github.com/cloudstateio/go-support/cloudstate/encoding"
	"github.com/cloudstateio/go-support/cloudstate/protocol"
	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes/any"
	"io"
	"net/url"
	"reflect"
	"strings"
	"sync"
)

// Entity
type Entity interface {
	EntityInitializer
}

// An EntityInitializer knows how to initialize an Entity
type EntityInitializer interface {
	New() interface{}
}

const snapshotEveryDefault = 100

// EventSourcedEntity captures an Entity, its ServiceName and PersistenceID.
// It is used to be registered as an event sourced entity on a CloudState instance.
//#event-sourced-entity-type
type EventSourcedEntity struct {
	// Entity is a nil or Zero-Initialized reference
	// to the entity to be event sourced. It has to
	// implement the EntityInitializer interface
	// so that CloudState can create new entity instances.
	Entity Entity
	// ServiceName is used to…
	// Setting it is optional.
	ServiceName string
	// PersistenceID is used to namespace events in the journal, useful for
	// when you share the same database between multiple entities. It defaults to
	// the simple name for the entity type.
	// It’s good practice to select one explicitly, this means your database
	// isn’t depend on type names in your code.
	// Setting it is optional.
	PersistenceID string
	// The snapshotEvery parameter controls how often snapshots are taken,
	// so that the entity doesn't need to be recovered from the whole journal
	// each time it’s loaded. If left unset, it defaults to 100.
	// Setting it to a negative number will result in snapshots never being taken.
	SnapshotEvery int64

	// internal
	entityName   string
	registerOnce sync.Once
}
//#event-sourced-entity-type

// initZeroValue get its Entity type and Zero-Value it to
// something we can use as an initializer.
func (e *EventSourcedEntity) initZeroValue() error {
	if reflect.ValueOf(e.Entity).IsNil() {
		t := reflect.TypeOf(e.Entity)
		if t.Kind() == reflect.Ptr { // TODO: how deep can that go?
			t = t.Elem()
		}
		value := reflect.New(t).Interface()
		if ei, ok := value.(EntityInitializer); ok {
			e.Entity = ei
		} else {
			return errors.New("the Entity does not implement EntityInitializer")
		}
		e.entityName = t.Name()
		e.SnapshotEvery = snapshotEveryDefault
	}
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

// EventSourcedHandler is the implementation of the EventSourcedHandler server API for EventSourced service.
type EventSourcedHandler struct {
	// entities are indexed by their service name
	entities map[string]*EventSourcedEntity
	// contexts are entity instance contexts indexed by their entity ids
	contexts map[string]*EntityInstanceContext
	// cmdMethodCache is the command handler method cache
	cmdMethodCache map[string]reflect.Method
}

// NewEventSourcedHandler returns an initialized EventSourcedHandler
func NewEventSourcedHandler() *EventSourcedHandler {
	return &EventSourcedHandler{
		entities:       make(map[string]*EventSourcedEntity),
		contexts:       make(map[string]*EntityInstanceContext),
		cmdMethodCache: make(map[string]reflect.Method),
	}
}

func (esh *EventSourcedHandler) registerEntity(ese *EventSourcedEntity) error {
	esh.entities[ese.ServiceName] = ese
	return nil
}

// Handle
// from EventSourcedServer.Handle
// The stream. One stream will be established per active entity.
// Once established, the first message sent will be Init, which contains the entity ID, and,
// if the entity has previously persisted a snapshot, it will contain that snapshot. It will
// then send zero to many event messages, one for each event previously persisted. The entity
// is expected to apply these to its state in a deterministic fashion. Once all the events
// are sent, one to many commands are sent, with new commands being sent as new requests for
// the entity come in. The entity is expected to reply to each command with exactly one reply
// message. The entity should reply in order, and any events that the entity requests to be
// persisted the entity should handle itself, applying them to its own state, as if they had
// arrived as events when the event stream was being replayed on load.
func (esh *EventSourcedHandler) Handle(server protocol.EventSourced_HandleServer) error {
	var entityId string
	var failed error
	for {
		if failed != nil {
			return failed
		}
		msg, recvErr := server.Recv()
		if recvErr == io.EOF {
			return nil
		}
		if recvErr != nil {
			return recvErr
		}
		if cmd := msg.GetCommand(); cmd != nil {
			if err := esh.handleCommand(cmd, server); err != nil {
				// TODO: in general, what happens with the stream here if an error happens?
				failed = handleFailure(err, server, cmd.GetId())
			}
			continue
		}
		if event := msg.GetEvent(); event != nil {
			// TODO spec: Why does command carry the entityId and an event not?
			if err := esh.handleEvent(entityId, event); err != nil {
				failed = handleFailure(err, server, 0)
			}
			continue
		}
		if init := msg.GetInit(); init != nil {
			if err := esh.handleInit(init, server); err != nil {
				failed = handleFailure(err, server, 0)
			}
			entityId = init.GetEntityId()
			continue
		}
	}
}

func (esh *EventSourcedHandler) handleInit(init *protocol.EventSourcedInit, server protocol.EventSourced_HandleServer) error {
	eid := init.GetEntityId()
	if _, present := esh.contexts[eid]; present {
		return NewFailureError("unable to server.Send")
	}
	entity := esh.entities[init.GetServiceName()]
	if initializer, ok := entity.Entity.(EntityInitializer); ok {
		instance := initializer.New()
		esh.contexts[eid] = &EntityInstanceContext{
			EntityInstance: &EntityInstance{
				Instance:           instance,
				EventSourcedEntity: entity,
			},
			active: true,
		}
	} else {
		return fmt.Errorf("unable to handle init entity.Entity does not implement EntityInitializer")
	}

	if err := esh.handleInitSnapshot(init); err != nil {
		return NewFailureError("unable to server.Send. %w", err)
	}
	esh.subscribeEvents(esh.contexts[eid].EntityInstance)
	return nil
}

func (esh *EventSourcedHandler) handleInitSnapshot(init *protocol.EventSourcedInit) error {
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

func (EventSourcedHandler) unmarshalSnapshot(init *protocol.EventSourcedInit) (interface{}, error) {
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

func (esh *EventSourcedHandler) subscribeEvents(instance *EntityInstance) {
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

func (esh *EventSourcedHandler) handleEvent(entityId string, event *protocol.EventSourcedEvent) error {
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

// handleCommand handles a command received from the CloudState proxy.
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
// return its response as a reply to the CloudState proxy.
//
// Events:
// Beside calling the service method, we have to collect "events" the service might emit.
// These events afterwards have to be handled by a EventHandler to update the state of the
// entity. The CloudState proxy can re-play these events at any time
func (esh *EventSourcedHandler) handleCommand(cmd *protocol.Command, server protocol.EventSourced_HandleServer) error {
	// method to call
	method, err := esh.methodToCall(cmd)
	if err != nil {
		return NewProtocolFailure(protocol.Failure{
			CommandId:   cmd.GetId(),
			Description: err.Error(),
		})
	}
	entityContext := esh.contexts[cmd.GetEntityId()]
	// build the input arguments for the method we're about to call
	inputs, err := esh.buildInputs(entityContext, method, cmd, server.Context())
	if err != nil {
		return NewProtocolFailure(protocol.Failure{
			CommandId:   cmd.GetId(),
			Description: err.Error(),
		})
	}
	// call it
	called := method.Func.Call(inputs)
	// The gRPC implementation returns the rpc return method
	// and an error as a second return value.
	errReturned := called[1]
	if errReturned.CanInterface() && errReturned.Interface() != nil && errReturned.Type().Name() == "error" {
		// TCK says: TODO Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
		return NewProtocolFailure(protocol.Failure{
			CommandId:   cmd.GetId(),
			Description: errReturned.Interface().(error).Error(),
		})
	}
	// the reply
	callReply, err := marshalAny(called[0].Interface())
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

func (*EventSourcedHandler) buildInputs(entityContext *EntityInstanceContext, method reflect.Method, cmd *protocol.Command, ctx context.Context) ([]reflect.Value, error) {
	inputs := make([]reflect.Value, method.Type.NumIn())
	inputs[0] = reflect.ValueOf(entityContext.EntityInstance.Instance)
	inputs[1] = reflect.ValueOf(ctx)
	// create a zero-value for the type of the message we call the method with
	arg1 := method.Type.In(2)
	ptr := false
	for arg1.Kind() == reflect.Ptr {
		ptr = true
		arg1 = arg1.Elem()
	}
	var msg proto.Message
	if ptr {
		msg = reflect.New(arg1).Interface().(proto.Message)
	} else {
		msg = reflect.Zero(arg1).Interface().(proto.Message)
	}
	if err := proto.Unmarshal(cmd.GetPayload().GetValue(), msg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal: %w", err)
	}
	inputs[2] = reflect.ValueOf(msg)
	return inputs, nil
}

func (esh *EventSourcedHandler) methodToCall(cmd *protocol.Command) (reflect.Method, error) {
	entityContext := esh.contexts[cmd.GetEntityId()]
	cacheKey := entityContext.ServiceName() + cmd.Name
	method, hit := esh.cmdMethodCache[cacheKey]
	// as measured this cache saves us about 75% of a call
	// to be prepared with 4.4µs vs. 17.6µs where a typical
	// call by reflection like GetCart() with Func.Call()
	// takes ~10µs and to get return values processed somewhere 0.7µs.
	if !hit {
		entityValue := reflect.ValueOf(entityContext.EntityInstance.Instance)
		// entities implement the proxied grpc service
		// we try to find the method we're called by name with the
		// received command.
		methodByName := entityValue.MethodByName(cmd.Name)
		if !methodByName.IsValid() {
			entity := esh.entities[entityContext.ServiceName()]
			return reflect.Method{}, fmt.Errorf("no method named: %s found for: %v", cmd.Name, entity)
		}
		// gRPC services are unary rpc methods, always.
		// They have one message in and one message out.
		if err := checkUnary(methodByName); err != nil {
			return reflect.Method{}, err
		}
		// The first argument in the gRPC implementation
		// is always a context.Context.
		methodArg0Type := methodByName.Type().In(0)
		contextType := reflect.TypeOf(context.Background())
		if !contextType.Implements(methodArg0Type) {
			return reflect.Method{}, fmt.Errorf(
				"first argument for method: %s is not of type: %s",
				methodByName.String(), contextType.Name(),
			)
		}
		// we'll find one for sure as we found one on the entityValue
		method, _ = reflect.TypeOf(entityContext.EntityInstance.Instance).MethodByName(cmd.Name)
		esh.cmdMethodCache[cacheKey] = method
	}
	return method, nil
}

func (*EventSourcedHandler) handleSnapshots(entityContext *EntityInstanceContext) (*any.Any, error) {
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

func checkUnary(methodByName reflect.Value) error {
	if methodByName.Type().NumIn() != 2 {
		return NewFailureError("method: %s is no unary method", methodByName.String())
	}
	return nil
}

// applyEvent applies an event to a local entity
func (esh EventSourcedHandler) applyEvent(entityInstance *EntityInstance, event interface{}) error {
	payload, err := marshalAny(event)
	if err != nil {
		return err
	}
	return esh.handleEvents(entityInstance, &protocol.EventSourcedEvent{Payload: payload})
}

// handleEvents handles a list of events encoded as protobuf Any messages.
//
// Event sourced entities persist events and snapshots, and these need to be
// serialized when persisted. The most straight forward way to persist events
// and snapshots is to use protobufs. CloudState will automatically detect if
// an emitted event is a protobuf, and serialize it as such. For other
// serialization options, including JSON, see Serialization.
func (EventSourcedHandler) handleEvents(entityInstance *EntityInstance, events ...*protocol.EventSourcedEvent) error {
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
					handled := false
					if implementsEventHandler {
						handled, err = eventHandler.HandleEvent(message)
						if err != nil {
							return err // FIXME/TODO: is this correct? if we fail here, nothing is safe afterwards.
						}
					}
					// if not, we try to find one
					// currently we support a method that has one argument that equals
					// to the type of the message received.
					if !handled {
						// find a concrete handling method
						entityValue := reflect.ValueOf(entityInstance.Instance)
						entityType := entityValue.Type()
						for n := 0; n < entityType.NumMethod(); n++ {
							method := entityType.Method(n)
							// we expect one argument for now, the domain message
							// the first argument is the receiver itself
							if method.Func.Type().NumIn() == 2 {
								argumentType := method.Func.Type().In(1)
								if argumentType.AssignableTo(messageType) {
									entityValue.MethodByName(method.Name).Call([]reflect.Value{reflect.ValueOf(message)})
								}
							} else {
								// we have not found a one-argument method matching the events type as an argument
								// TODO: what to do here? we might support more variations of possible handlers we can detect
							}
						}
					}
				}
			}
		} // TODO: what do we do if we haven't handled the events?
	}
	return nil
}
