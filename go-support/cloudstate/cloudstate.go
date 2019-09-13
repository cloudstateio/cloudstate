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

// Package cloudstate implements the CloudState event sourced and entity discovery protocol.
package cloudstate

import (
	"cloudstate.io/gosupport/cloudstate/protocol"
	"context"
	"errors"
	"fmt"
	"github.com/golang/protobuf/descriptor"
	"github.com/golang/protobuf/proto"
	filedescr "github.com/golang/protobuf/protoc-gen-go/descriptor"
	"github.com/golang/protobuf/ptypes/empty"
	"google.golang.org/grpc"
	"log"
	"net"
	"os"
	"runtime"
)

const (
	SupportLibraryVersion = "0.0.1"
	SupportLibraryName    = "cloudstate-go-support"
	ServiceVersion        = "0.0.1"
)

type CloudState struct {
	server                   *grpc.Server
	entityDiscoveryResponder *EntityDiscoveryResponder
	eventSourcedHandler      *EventSourcedHandler
}

// NewCloudState returns a new CloudState instance.
func NewCloudState(options *Options) *CloudState {
	cs := &CloudState{
		server:                   grpc.NewServer(),
		entityDiscoveryResponder: NewEntityDiscoveryResponder(options),
		eventSourcedHandler:      NewEventSourcedHandler(),
	}

	protocol.RegisterEntityDiscoveryServer(cs.server, cs.entityDiscoveryResponder)
	log.Println("RegisterEntityDiscoveryServer")
	protocol.RegisterEventSourcedServer(cs.server, cs.eventSourcedHandler)
	log.Println("RegisterEventSourcedServer")
	return cs
}

// Options go get a CloudState instance configured.
type Options struct {
	ServiceName    string
	ServiceVersion string
}

var NoOptions = Options{}

// Register registers an event sourced entity for CloudState.
func (cs *CloudState) Register(ese *EventSourcedEntity) (err error) {
	ese.once.Do(func() {
		if err = ese.initZeroValue(); err != nil {
			return
		}
		if err = cs.eventSourcedHandler.registerEntity(ese); err != nil {
			return
		}
		if err = cs.entityDiscoveryResponder.registerEntity(ese); err != nil {
			return
		}
	})
	return
}

// Run runs the CloudState instance.
func (cs *CloudState) Run() {
	host, ok := os.LookupEnv("HOST")
	if !ok {
		log.Fatalf("unable to get environment variable \"HOST\"")
	}
	port, ok := os.LookupEnv("PORT")
	if !ok {
		log.Fatalf("unable to get environment variable \"PORT\"")
	}
	lis, err := net.Listen("tcp", fmt.Sprintf("%s:%s", host, port))
	if err != nil {
		log.Fatalf("failed to listen: %v\n", err)
	}
	log.Printf("starting grpcServer at: %s:%s", host, port)
	if e := cs.server.Serve(lis); e != nil {
		log.Fatalf("failed to grpcServer.Serve for: %v", lis)
	}
}

// EntityDiscoveryResponder implements the CloudState discovery protocol.
type EntityDiscoveryResponder struct {
	fileDescriptorSet *filedescr.FileDescriptorSet
	entitySpec        *protocol.EntitySpec
	message           *descriptor.Message
}

// NewEntityDiscoveryResponder returns a new and initialized EntityDiscoveryResponder.
func NewEntityDiscoveryResponder(options *Options) *EntityDiscoveryResponder {
	responder := &EntityDiscoveryResponder{}
	responder.reset()
	responder.entitySpec.ServiceInfo.ServiceName = options.ServiceName
	responder.entitySpec.ServiceInfo.ServiceVersion = options.ServiceVersion
	return responder
}

// Discover returns an entity spec for
func (r *EntityDiscoveryResponder) Discover(c context.Context, pi *protocol.ProxyInfo) (*protocol.EntitySpec, error) {
	log.Printf("Received discovery call from sidecar [%s w%s] supporting CloudState %v.%v\n",
		pi.ProxyName,
		pi.ProxyVersion,
		pi.ProtocolMajorVersion,
		pi.ProtocolMinorVersion,
	)
	for _, filename := range []string{
		"google/protobuf/empty.proto",
		"google/protobuf/any.proto",
		"google/protobuf/descriptor.proto",
		"google/api/annotations.proto",
		"google/api/http.proto",
		"cloudstate/event_sourced.proto",
		"cloudstate/entity.proto",
		"cloudstate/entity_key.proto",
	} {
		if err := r.registerFiledescriptorProto(filename); err != nil {
			return nil, err
		}
	}
	log.Printf("Responding with: %v\n", r.entitySpec.GetServiceInfo())
	return r.entitySpec, nil
}

func (r *EntityDiscoveryResponder) ReportError(c context.Context, ufe *protocol.UserFunctionError) (*empty.Empty, error) {
	log.Printf("ReportError: %v\n", ufe)
	return &empty.Empty{}, nil
}

func (r *EntityDiscoveryResponder) reset() {
	r.entitySpec = &protocol.EntitySpec{
		Entities: make([]*protocol.Entity, 0)[:],
		ServiceInfo: &protocol.ServiceInfo{
			ServiceName:           "shopping-chart",
			ServiceVersion:        ServiceVersion,
			ServiceRuntime:        runtime.Version(),
			SupportLibraryName:    SupportLibraryName,
			SupportLibraryVersion: SupportLibraryVersion,
		},
	}
	r.fileDescriptorSet = &filedescr.FileDescriptorSet{
		File: make([]*filedescr.FileDescriptorProto, 0)[:],
	}
}

func (r *EntityDiscoveryResponder) updateSpec() (err error) {
	if protoBytes, e := proto.Marshal(r.fileDescriptorSet); e == nil {
		r.entitySpec.Proto = protoBytes
		return nil
	} else {
		return errors.New("unable to Marshal FileDescriptorSet")
	}
}

func (r *EntityDiscoveryResponder) registerEntity(e *EventSourcedEntity) error {
	message, ok := e.Entity.(descriptor.Message)
	if !ok {
		return errors.New("entity is no descriptor.Message")
	}
	r.entitySpec.Entities = append(r.entitySpec.Entities, &protocol.Entity{
		EntityType:    EventSourced,
		ServiceName:   e.ServiceName,
		PersistenceId: e.entityName,
	})

	// TODO: how about adding fd.Dependency along this call
	fd, _ := descriptor.ForMessage(message)
	r.fileDescriptorSet.File = append(r.fileDescriptorSet.File, fd)
	return r.updateSpec()
}

func (r *EntityDiscoveryResponder) registerFiledescriptorProto(filename string) (err error) {
	if descriptorProto, e := unpackFile(proto.FileDescriptor(filename)); e != nil {
		return e
	} else {
		r.fileDescriptorSet.File = append(r.fileDescriptorSet.File, descriptorProto)
		return r.updateSpec()
	}
}
