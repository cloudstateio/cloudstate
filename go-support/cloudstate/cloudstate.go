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
	SupportLibraryVersion = "0.4.4"
	SupportLibraryName    = "cloudstate-go-support"
)

// CloudState is an instance of a CloudState User Function
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

// DescriptorConfig configures service and dependent descriptors.
type DescriptorConfig struct {
	Service        string
	ServiceMsg     descriptor.Message
	Domain         []string
	DomainMessages []descriptor.Message
}

func (dc DescriptorConfig) AddDomainMessage(m descriptor.Message) DescriptorConfig {
	dc.DomainMessages = append(dc.DomainMessages, m)
	return dc
}

func (dc DescriptorConfig) AddDomainDescriptor(filename string) DescriptorConfig {
	dc.Domain = append(dc.Domain, filename)
	return dc
}

// Register registers an event sourced entity for CloudState.
func (cs *CloudState) Register(ese *EventSourcedEntity, config DescriptorConfig) (err error) {
	ese.once.Do(func() {
		if err = ese.initZeroValue(); err != nil {
			return
		}
		if err = cs.eventSourcedHandler.registerEntity(ese); err != nil {
			return
		}
		if err = cs.entityDiscoveryResponder.registerEntity(ese, config); err != nil {
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
	responder.entitySpec = &protocol.EntitySpec{
		Entities: make([]*protocol.Entity, 0),
		ServiceInfo: &protocol.ServiceInfo{
			ServiceName:           options.ServiceName,
			ServiceVersion:        options.ServiceVersion,
			ServiceRuntime:        fmt.Sprintf("%s %s/%s", runtime.Version(), runtime.GOOS, runtime.GOARCH),
			SupportLibraryName:    SupportLibraryName,
			SupportLibraryVersion: SupportLibraryVersion,
		},
	}
	responder.fileDescriptorSet = &filedescr.FileDescriptorSet{
		File: make([]*filedescr.FileDescriptorProto, 0),
	}
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
		if err := r.registerFileDescriptorProto(filename); err != nil {
			return nil, err
		}
	}
	log.Printf("Responding with: %v\n", r.entitySpec.GetServiceInfo())
	return r.entitySpec, nil
}

// ReportError logs
func (r *EntityDiscoveryResponder) ReportError(c context.Context, fe *protocol.UserFunctionError) (*empty.Empty, error) {
	log.Printf("ReportError: %v\n", fe)
	return &empty.Empty{}, nil
}

func (r *EntityDiscoveryResponder) updateSpec() (err error) {
	protoBytes, err := proto.Marshal(r.fileDescriptorSet)
	if err != nil {
		return errors.New("unable to Marshal FileDescriptorSet")
	}
	r.entitySpec.Proto = protoBytes
	return nil
}

func (r *EntityDiscoveryResponder) resolveFileDescriptors(dc DescriptorConfig) error {
	// service
	if dc.Service != "" {
		if err := r.registerFileDescriptorProto(dc.Service); err != nil {
			return err
		}
	} else {
		if dc.ServiceMsg != nil {
			if err := r.registerFileDescriptor(dc.ServiceMsg); err != nil {
				return err
			}
		}
	}
	// and dependent domain descriptors
	for _, dp := range dc.Domain {
		if err := r.registerFileDescriptorProto(dp); err != nil {
			return err
		}
	}
	for _, dm := range dc.DomainMessages {
		if err := r.registerFileDescriptor(dm); err != nil {
			return err
		}
	}
	return nil
}

func (r *EntityDiscoveryResponder) registerEntity(e *EventSourcedEntity, config DescriptorConfig) error {
	if err := r.resolveFileDescriptors(config); err != nil {
		return fmt.Errorf("failed to resolveFileDescriptor for DescriptorConfig: %+v: %w", config, err)
	}
	persistenceID := e.entityName
	if e.PersistenceID != "" {
		persistenceID = e.PersistenceID
	}
	r.entitySpec.Entities = append(r.entitySpec.Entities, &protocol.Entity{
		EntityType:    EventSourced,
		ServiceName:   e.ServiceName,
		PersistenceId: persistenceID,
	})
	return r.updateSpec()
}

func (r *EntityDiscoveryResponder) registerFileDescriptorProto(filename string) error {
	descriptorProto, err := unpackFile(proto.FileDescriptor(filename))
	if err != nil {
		return fmt.Errorf("failed to registerFileDescriptorProto for filename: %s: %w", filename, err)
	}
	r.fileDescriptorSet.File = append(r.fileDescriptorSet.File, descriptorProto)
	return r.updateSpec()
}

func (r *EntityDiscoveryResponder) registerFileDescriptor(msg descriptor.Message) error {
	fd, _ := descriptor.ForMessage(msg) // this can panic, so we do here
	r.fileDescriptorSet.File = append(r.fileDescriptorSet.File, fd)
	return nil
}
