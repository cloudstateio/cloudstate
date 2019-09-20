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
	"cloudstate.io/gosupport/cloudstate/protocol"
	"errors"
	"fmt"
	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes/any"
	"io"
	"log"
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

type EntityInstance struct {
	EventSourcedEntity *EventSourcedEntity
	Instance           interface{}
}

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
	SnapshotEvery int

	// internal
	entityName string
	once       sync.Once
}

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
	}
	return nil
}

// A EntityInstanceContext represents a event sourced entity together with its
// associated service.
// Commands are dispatched through this context.
type EntityInstanceContext struct {
	EntityInstance EntityInstance
}

// ServiceName returns the contexts service name.
func (ec *EntityInstanceContext) ServiceName() string {
	return ec.EntityInstance.EventSourcedEntity.ServiceName
}

// EventSourcedHandler is the implementation of the EventSourcedHandler server API for EventSourced service.
type EventSourcedHandler struct {
	// entities are indexed by their service name
	entities map[string]*EventSourcedEntity
	// entity instance contexts for all
	// event sourced entities indexed by their entity ids
	contexts map[string]*EntityInstanceContext
	// method cache
	methodCache map[string]reflect.Method
}

// NewEventSourcedHandler returns an initialized EventSourcedHandler
func NewEventSourcedHandler() *EventSourcedHandler {
	return &EventSourcedHandler{
		entities:    make(map[string]*EventSourcedEntity),
		contexts:    make(map[string]*EntityInstanceContext),
		methodCache: make(map[string]reflect.Method),
	}
}

func (esh *EventSourcedHandler) registerEntity(ese *EventSourcedEntity) error {
	esh.entities[ese.ServiceName] = ese
	return nil
}

// see EventSourcedServer.Handle
func (esh *EventSourcedHandler) Handle(server protocol.EventSourced_HandleServer) error {
	for {
		msg, err := server.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if cmd := msg.GetCommand(); cmd != nil {
			if err := esh.handleCommand(cmd, server); err != nil {
				if errors.Is(err, ErrSendFailure) {
				}
				return err
			}
		}
		if event := msg.GetEvent(); event != nil {
			log.Fatalf("event handling is not implemented yet")
		}
		if init := msg.GetInit(); init != nil {
			if err := esh.handleInit(init, server); err != nil { // TODO: unwrap the error and see if its a server.Send error
				return err
			}
		}
	}
}

func (esh *EventSourcedHandler) handleInit(init *protocol.EventSourcedInit, server protocol.EventSourced_HandleServer) error {
	eid := init.GetEntityId()
	if _, present := esh.contexts[eid]; present {
		if err := server.Send(&protocol.EventSourcedStreamOut{
			Message: &protocol.EventSourcedStreamOut_Failure{
				Failure: &protocol.Failure{
					Description: "entity already initialized",
				}}}); err != nil {
			return fmt.Errorf("unable to server.Send, %w", err)
		}
		return nil
	}
	entity := esh.entities[init.GetServiceName()]
	if initializer, ok := entity.Entity.(EntityInitializer); ok {
		instance := initializer.New()
		esh.contexts[eid] = &EntityInstanceContext{
			EntityInstance: EntityInstance{
				Instance:           instance,
				EventSourcedEntity: entity,
			},
		}
	} else {
		return fmt.Errorf("unable to handle init entity.Entity does not implement EntityInitializer")
	}
	return nil
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
// (TODO: check sequencing of the events)
// (TODO: when has this to be happen? right after a command was handled?, most probably => for every single call of Emit => FIXME)
func (esh *EventSourcedHandler) handleCommand(cmd *protocol.Command, server protocol.EventSourced_HandleServer) error {
	entityContext := esh.contexts[cmd.GetEntityId()]
	entity := esh.entities[entityContext.ServiceName()]
	entityValue := reflect.ValueOf(entityContext.EntityInstance.Instance)

	cacheKey := entityContext.ServiceName() + cmd.Name
	method, hit := esh.methodCache[cacheKey]
	// as measured this cache saves us about 75% of a call
	// to be prepared with 4.4µs vs. 17.6µs where a typical
	// call by reflection like GetCart() with Func.Call()
	// takes ~10µs and to get return values processed somewhere 0.7µs.
	if !hit {
		// entities implement the proxied grpc service
		// we try to find the method we're called by name with the
		// received command.
		methodByName := entityValue.MethodByName(cmd.Name)
		if !methodByName.IsValid() {
			return fmt.Errorf("no method named: %s found for: %v", cmd.Name, entity)
		}
		// gRPC services are unary rpc methods, always.
		// They have one message in and one message out.
		if methodByName.Type().NumIn() != 2 {
			// this should never happen
			failure := &protocol.Failure{
				Description: fmt.Sprintf("non-unary method %s of entity: %v found", methodByName.String(), entityValue.String()),
			}
			if err := sendFailure(failure, server); err != nil {
				return ErrSendFailure
			}
		}
		// The first argument in the gRPC implementation
		// is always a context.Context.
		methodArg0Type := methodByName.Type().In(0)
		if !reflect.TypeOf(server.Context()).Implements(methodArg0Type) {
			return fmt.Errorf(
				"first argument for method: %s is not of type: %s",
				methodByName.String(), reflect.TypeOf(server.Context()).Name(),
			)
		}
		method, _ = reflect.TypeOf(entityContext.EntityInstance.Instance).MethodByName(cmd.Name)
		esh.methodCache[cacheKey] = method
	}

	// build the input arguments for the method we're about to call
	inputs := make([]reflect.Value, method.Type.NumIn())
	inputs[0] = entityValue
	inputs[1] = reflect.ValueOf(server.Context())

	// create a zero-value for the type of the
	// message we call the method with
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
		return fmt.Errorf("failed to unmarshal: %w", err)
	}

	inputs[2] = reflect.ValueOf(msg)
	// call it
	called := method.Func.Call(inputs)
	// The gRPC implementation returns the rpc return method
	// and an error as a second return value.
	errReturned := called[1]
	if errReturned.Interface() != nil && errReturned.Type().Name() == "error" { // FIXME: looks ugly
		// TCK says: FIXME Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
		failure := &protocol.Failure{
			CommandId:   cmd.GetId(),
			Description: errReturned.Interface().(error).Error(),
		}
		if err := sendClientActionFailure(failure, server); err != nil {
			return ErrSendFailure
		}
	}
	// the reply
	callReply, ok := called[0].Interface().(proto.Message)
	if !ok {
		// this should never happen
		return fmt.Errorf("called return value at index 0 is no proto.Message")
	}
	typeUrl := fmt.Sprintf("%s/%s", protoAnyBase, proto.MessageName(callReply))
	marshal, err := proto.Marshal(callReply)
	if err != nil {
		return fmt.Errorf("unable to Marshal command reply: %w", ErrMarshal)
	}

	// emitted events
	events, err := esh.marshalEventsTo(entityValue)
	if err != nil {
		return err
	}
	esh.handleEvents(entityValue, events)
	err = sendEventSourcedReply(&protocol.EventSourcedReply{
		CommandId: cmd.GetId(),
		ClientAction: &protocol.ClientAction{
			Action: &protocol.ClientAction_Reply{
				Reply: &protocol.Reply{
					Payload: &any.Any{
						TypeUrl: typeUrl,
						Value:   marshal,
					},
				},
			},
		},
		Events: events,
	}, server)
	if err != nil {
		return fmt.Errorf("%s, %w", err, ErrSend)
	}
	return nil
}

// marshalEventsTo receives the events emitted through the handling of a command
// and marshals them to the event serialized form.
func (esh *EventSourcedHandler) marshalEventsTo(entityValue reflect.Value) ([]*any.Any, error) {
	events := make([]*any.Any, 0)
	if emitter, ok := entityValue.Interface().(EventEmitter); ok {
		for _, evt := range emitter.Events() {
			// TODO: protobufs are expected here, but CloudState supports other formats
			message, ok := evt.(proto.Message)
			if !ok {
				return nil, fmt.Errorf("got a non-proto message as event")
			}
			marshal, err := proto.Marshal(message)
			if err != nil {
				return nil, fmt.Errorf("%s, %w", err, ErrMarshal)
			}
			events = append(events,
				&any.Any{
					TypeUrl: fmt.Sprintf("%s/%s", protoAnyBase, proto.MessageName(message)),
					Value:   marshal,
				},
			)
		}
		emitter.Clear()
	}
	return events, nil
}

// handleEvents handles a list of events encoded as protobuf Any messages.
//
// Event sourced entities persist events and snapshots, and these need to be
// serialized when persisted. The most straight forward way to persist events
// and snapshots is to use protobufs. CloudState will automatically detect if
// an emitted event is a protobuf, and serialize it as such. For other
// serialization options, including JSON, see Serialization.
func (esh *EventSourcedHandler) handleEvents(entityValue reflect.Value, events []*any.Any) error {
	eventHandler, implementsEventHandler := entityValue.Interface().(EventHandler)
	for _, event := range events {
		// TODO: here's the point where events can be protobufs, serialized as json or other formats
		msgName := strings.TrimPrefix(event.GetTypeUrl(), protoAnyBase+"/")
		messageType := proto.MessageType(msgName)

		// messageType would be: domain.ItemAdded
		if messageType.Kind() == reflect.Ptr {
			// get a zero-ed message of this type
			if message, ok := reflect.New(messageType.Elem()).Interface().(proto.Message); ok {
				// and marshal onto it what we got as an any.Any onto it
				err := proto.Unmarshal(event.Value, message)
				if err != nil {
					return fmt.Errorf("%s, %w", err, ErrMarshal)
				} else {
					// we're ready to handle the proto message
					// and we might have a handler
					handled := false
					if implementsEventHandler {
						handled, err = eventHandler.HandleEvent(message)
						if err != nil {
							return err
						}
					}
					// if not, we try to find one
					// currently we support a method that has one argument that equals
					// to the type of the message received.
					if !handled {
						// find a concrete handling method
						entityType := entityValue.Type()
						for tmi := 0; tmi < entityType.NumMethod(); tmi++ {
							method := entityType.Method(tmi)
							// we expect one argument for now, the domain message
							// the first argument is the receiver itself
							if method.Func.Type().NumIn() == 2 {
								argumentType := method.Func.Type().In(1)
								if argumentType.AssignableTo(messageType) {
									entityValue.MethodByName(method.Name).Call([]reflect.Value{reflect.ValueOf(message)})
								}
							} else {
								// we have not found a one-argument method maching the
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
