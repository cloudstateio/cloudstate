package main

import (
	"cloudstate.io/gosupport/cloudstate/protocol"
	"cloudstate.io/gosupport/shoppingcart"
	"context"
	"fmt"
	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes/empty"
	"google.golang.org/grpc"
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

func (edr *EntityDiscoveryResponder) Discover(c context.Context, pi *protocol.ProxyInfo) (*protocol.EntitySpec, error) {
	fmt.Printf("Discover: %v\n", pi)
	fmt.Printf("Received discovery call from sidecar [%s %s] supporting CloudState %v.%v\n", pi.ProxyName, pi.ProxyVersion, pi.ProtocolMajorVersion, pi.ProtocolMinorVersion)

	entities := make([]*protocol.Entity, 0)
	entities = append(entities, &protocol.Entity{
		ServiceName:   "com.example.shoppingcart.ShoppingCart",
		EntityType:    "cloudstate.eventsourced.EventSourced",
		PersistenceId: "0",
	})

	//fd, md := descriptor.ForMessage(&shoppingcart.Cart{})
	fd := proto.FileDescriptor("shoppingcart/shoppingcart.proto")

	es := protocol.EntitySpec{
		Proto:    fd,
		Entities: entities,
		ServiceInfo: &protocol.ServiceInfo{
			ServiceName:           "shopping-chart",
			ServiceVersion:        "0.0.1",
			ServiceRuntime:        runtime.Version(),
			SupportLibraryName:    "cloudstate-go-support",
			SupportLibraryVersion: "0.0.1",
		},
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
