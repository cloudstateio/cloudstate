package main

import (
	"bytes"
	"cloudstate.io/gosupport/cloudstate/protocol"
	"cloudstate.io/gosupport/shoppingcart"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"github.com/golang/protobuf/descriptor"
	"github.com/golang/protobuf/proto"
	descriptor2 "github.com/golang/protobuf/protoc-gen-go/descriptor"
	"github.com/golang/protobuf/ptypes/empty"
	"google.golang.org/grpc"
	"io/ioutil"
	"log"
	"net"
	"runtime"
)

type CartServer struct {
}

func (s *CartServer) AddItem(c context.Context, li *shoppingcart.AddLineItem) (*empty.Empty, error) {
	fmt.Printf("AddItem: %v\n", li)
	return &empty.Empty{}, nil
}

func (s *CartServer) RemoveItem(c context.Context, li *shoppingcart.RemoveLineItem) (*empty.Empty, error) {
	fmt.Printf("RemoveItem: %v\n", li)
	return &empty.Empty{}, nil
}

func (s *CartServer) GetCart(c context.Context, sc *shoppingcart.GetShoppingCart) (*shoppingcart.Cart, error) {
	fmt.Printf("GetCart: %v\n", sc)
	return &shoppingcart.Cart{}, nil
}

type EntityDiscoveryResponder struct {
}

func appendTypeTo(msg descriptor.Message, es *protocol.EntitySpec, set *descriptor2.FileDescriptorSet) {
	fd, md := descriptor.ForMessage(msg)
	es.Entities = append(es.Entities, &protocol.Entity{
		ServiceName:   "com.example.shoppingcart.ShoppingCart", //fmt.Sprintf("%s.%s", fd.Package, fd.Service[0].Name),
		EntityType:    "cloudstate.eventsourced.EventSourced",
		PersistenceId: "0",
	})
	fmt.Printf("%v", md)
	set.File = append(set.File, fd)
	if bytes, e := proto.Marshal(set); e == nil {
		es.Proto = bytes
	} else {
		panic("huiii")
	}
}

func unpackFile(gz []byte) (*descriptor2.FileDescriptorProto, error) {
	r, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return nil, errors.New("failed to open gzip reader")
	}
	defer r.Close()
	b, err := ioutil.ReadAll(r)
	if err != nil {
		return nil, errors.New("failed to uncompress descriptor")
	}
	fd := new(descriptor2.FileDescriptorProto)
	if err := proto.Unmarshal(b, fd); err != nil {
		return nil, errors.New("malformed FileDescriptorProto")
	}
	return fd, nil
}

func unpackAndAdd(filename string, set *descriptor2.FileDescriptorSet) {
	descriptorProto, e := unpackFile(proto.FileDescriptor(filename))
	if e != nil {
		panic(e)
	}
	set.File = append(set.File, descriptorProto)
}

func (edr *EntityDiscoveryResponder) Discover(c context.Context, pi *protocol.ProxyInfo) (*protocol.EntitySpec, error) {
	fmt.Printf("Discover: %v\n", pi)
	fmt.Printf("Received discovery call from sidecar [%s %s] supporting CloudState %v.%v\n", pi.ProxyName, pi.ProxyVersion, pi.ProtocolMajorVersion, pi.ProtocolMinorVersion)
	es := protocol.EntitySpec{
		Proto:    nil,
		Entities: make([]*protocol.Entity, 0),
		ServiceInfo: &protocol.ServiceInfo{
			ServiceName:           "shopping-chart",
			ServiceVersion:        "0.0.1",
			ServiceRuntime:        runtime.Version(),
			SupportLibraryName:    "cloudstate-go-support",
			SupportLibraryVersion: "0.0.1",
		},
	}
	set := descriptor2.FileDescriptorSet{
		File: make([]*descriptor2.FileDescriptorProto, 0),
	}
	appendTypeTo(&shoppingcart.Cart{}, &es, &set)
	appendTypeTo(&empty.Empty{}, &es, &set)
	unpackAndAdd("cloudstate/entity_key.proto", &set)
	unpackAndAdd("google/protobuf/descriptor.proto", &set)
	unpackAndAdd("google/api/annotations.proto", &set)
	unpackAndAdd("google/api/http.proto", &set)

	if bytes, e := proto.Marshal(&set); e == nil {
		es.Proto = bytes
	} else {
		panic("huiii")
	}

	fmt.Printf("Responding with: %v\n", es.ServiceInfo)
	return &es, nil
}

func (edr *EntityDiscoveryResponder) ReportError(c context.Context, ufe *protocol.UserFunctionError) (*empty.Empty, error) {
	fmt.Printf("ReportError: %v\n", ufe)
	return &empty.Empty{}, nil
}

func main() {
	lis, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", 8080))
	if err != nil {
		log.Fatalf("failed to listen: %v\n", err)
	}

	grpcServer := grpc.NewServer()
	protocol.RegisterEntityDiscoveryServer(grpcServer, &EntityDiscoveryResponder{})
	fmt.Println("RegisterEntityDiscoveryServer")
	shoppingcart.RegisterShoppingCartServer(grpcServer, &CartServer{})
	fmt.Println("RegisterShoppingCartServer")

	//entities := make([]*protocol.Entity, 0)
	//entities = append(entities, &protocol.Entity{
	//	ServiceName: "com.example.shoppingcart.ShoppingCart",
	//	EntityType:  "cloudstate.eventsourced.EventSourced",
	//})
	//
	////fd := proto.FileDescriptor("shoppingcart/shoppingcart.proto")
	//fd, md := descriptor.ForMessage(&shoppingcart.Cart{})
	//fmt.Printf("v: %v, %v\n", fd, md)

	//_ := descriptor.FileDescriptorProto{}
	//_ := protocol.EventSourcedEvent{}
	//_ := generator.FileDescriptor{}

	grpcServer.Serve(lis)
}
