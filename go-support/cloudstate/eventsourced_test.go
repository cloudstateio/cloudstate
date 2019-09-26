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
	"context"
	"errors"
	"fmt"
	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes/any"
	"github.com/golang/protobuf/ptypes/empty"
	"google.golang.org/grpc"
	"os"
	"sync"
	"testing"
)

func TestErrSend(t *testing.T) {
	err0 := ErrSendFailure
	err1 := fmt.Errorf("on reply: %w", ErrSendFailure)
	if !errors.Is(err1, err0) {
		t.Fatalf("err1 is no err0 but should")
	}
}

type TestEntity struct {
	Value int64
	EventEmitter
}

func (te *TestEntity) IncrementBy(n int64) (int64, error) {
	te.Value += n
	return te.Value, nil
}

func (te *TestEntity) DecrementBy(n int64) (int64, error) {
	te.Value -= n
	return te.Value, nil
}

// initialize value to <0 let us check whether an initCommand works
var testEntity = &TestEntity{
	Value:        -1,
	EventEmitter: NewEmitter(),
}

func resetTestEntity() {
	testEntity = &TestEntity{
		Value:        -1,
		EventEmitter: NewEmitter(),
	}
}

func (te *TestEntity) New() interface{} {
	testEntity.Value = 0
	return testEntity
}

func (te *TestEntity) IncrementByCommand(c context.Context, ibc *IncrementByCommand) (*empty.Empty, error) {
	te.Emit(&IncrementByEvent{
		Value: ibc.Amount,
	})
	return &empty.Empty{}, nil
}

func (te *TestEntity) DecrementByCommand(c context.Context, ibc *DecrementByCommand) (*empty.Empty, error) {
	te.Emit(&DecrementByEvent{
		Value: ibc.Amount,
	})
	return &empty.Empty{}, nil
}

type IncrementByEvent struct {
	Value int64 `protobuf:"varint,2,opt,name=amount,proto3" json:"amount,omitempty"`
}

func (inc IncrementByEvent) String() string {
	return proto.CompactTextString(inc)
}

func (inc IncrementByEvent) ProtoMessage() {
}

func (inc IncrementByEvent) Reset() {
}

type DecrementByEvent struct {
	Value int64 `protobuf:"varint,2,opt,name=amount,proto3" json:"amount,omitempty"`
}

func (inc DecrementByEvent) String() string {
	return proto.CompactTextString(inc)
}

func (inc DecrementByEvent) ProtoMessage() {
}

func (inc DecrementByEvent) Reset() {
}

func (te *TestEntity) DecrementByEvent(d *DecrementByEvent) error {
	_, err := te.DecrementBy(d.Value)
	return err
}

func (te *TestEntity) HandleEvent(event interface{}) (handled bool, err error) {
	switch e := event.(type) {
	case *IncrementByEvent:
		_, err := te.IncrementBy(e.Value)
		return true, err
	default:
		return false, nil
	}
}

type IncrementByCommand struct {
	Amount int64 `protobuf:"varint,2,opt,name=amount,proto3" json:"amount,omitempty"`
}

func (inc IncrementByCommand) String() string {
	return proto.CompactTextString(inc)
}

func (inc IncrementByCommand) ProtoMessage() {
}

func (inc IncrementByCommand) Reset() {
}

type DecrementByCommand struct {
	Amount int64 `protobuf:"varint,2,opt,name=amount,proto3" json:"amount,omitempty"`
}

func (inc DecrementByCommand) String() string {
	return proto.CompactTextString(inc)
}

func (inc DecrementByCommand) ProtoMessage() {
}

func (inc DecrementByCommand) Reset() {
}

// TestEventSourcedHandleServer is a grpc.ServerStream mock
type TestEventSourcedHandleServer struct {
	grpc.ServerStream
}

func (t TestEventSourcedHandleServer) Context() context.Context {
	return context.Background()
}

func (t TestEventSourcedHandleServer) Send(out *protocol.EventSourcedStreamOut) error {
	return nil
}
func (t TestEventSourcedHandleServer) Recv() (*protocol.EventSourcedStreamIn, error) {
	return nil, nil
}

func newHandler(t *testing.T) *EventSourcedHandler {
	handler := NewEventSourcedHandler()
	entity := EventSourcedEntity{
		Entity:        (*TestEntity)(nil),
		ServiceName:   "TestEventSourcedHandler-Service",
		SnapshotEvery: 0,
		once:          sync.Once{},
	}
	err := entity.initZeroValue()
	if err != nil {
		t.Errorf("%v", err)
	}
	err = handler.registerEntity(&entity)
	if err != nil {
		t.Errorf("%v", err)
	}
	return handler
}

func initHandler(handler *EventSourcedHandler, t *testing.T) {
	err := handler.handleInit(&protocol.EventSourcedInit{
		ServiceName: "TestEventSourcedHandler-Service",
		EntityId:    "entity-0",
	}, nil)
	if err != nil {
		t.Errorf("%v", err)
		t.Fail()
	}
}

func marshal(msg proto.Message, t *testing.T) ([]byte, error) {
	cmd, err := proto.Marshal(msg)
	if err != nil {
		t.Errorf("%v", err)
	}
	return cmd, err
}

func TestMain(m *testing.M) {
	proto.RegisterType((*IncrementByEvent)(nil), "IncrementByEvent")
	proto.RegisterType((*DecrementByEvent)(nil), "DecrementByEvent")
	resetTestEntity()
	defer resetTestEntity()
	os.Exit(m.Run())
}

func TestEventSourcedHandlerHandlesCommandAndEvents(t *testing.T) {
	handler := newHandler(t)
	if testEntity.Value >= 0 {
		t.Errorf("testEntity.Value should be <0 but was not: %+v", testEntity)
	}
	initHandler(handler, t)
	if testEntity.Value != 0 {
		t.Errorf("testEntity.Value should be 0 but was not: %+v", testEntity)
	}
	incrementedTo := int64(7)
	incrCmdValue, err := marshal(&IncrementByCommand{Amount: incrementedTo}, t)
	incrCommand := protocol.Command{
		EntityId: "entity-0",
		Id:       1,
		Name:     "IncrementByCommand",
		Payload: &any.Any{
			TypeUrl: "type.googleapis.com/IncrementByCommand",
			Value:   incrCmdValue,
		},
	}
	err = handler.handleCommand(&incrCommand, TestEventSourcedHandleServer{})
	if err != nil {
		t.Errorf("%v", err)
	}
	if testEntity.Value != incrementedTo {
		t.Errorf("testEntity.Value != incrementedTo")
	}

	decrCmdValue, err := proto.Marshal(&DecrementByCommand{Amount: incrementedTo})
	if err != nil {
		t.Errorf("%v", err)
	}
	decrCommand := protocol.Command{
		EntityId: "entity-0",
		Id:       1,
		Name:     "DecrementByCommand",
		Payload: &any.Any{
			TypeUrl: "type.googleapis.com/DecrementByCommand",
			Value:   decrCmdValue,
		},
	}
	err = handler.handleCommand(&decrCommand, TestEventSourcedHandleServer{})
	if err != nil {
		t.Errorf("%v", err)
	}
	if testEntity.Value != 0 {
		t.Errorf("testEntity.Value != incrementedTo")
	}
}
