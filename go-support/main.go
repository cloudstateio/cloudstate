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
	"github.com/golang/protobuf/ptypes/any"
	"github.com/golang/protobuf/ptypes/empty"
	"google.golang.org/grpc"
	"io"
	"io/ioutil"
	"log"
	"net"
	"os"
	"runtime"
)

type EntityDiscoveryResponder struct {
}

func appendTypeTo(msg descriptor.Message, es *protocol.EntitySpec, set *descriptor2.FileDescriptorSet) {
	fd, md := descriptor.ForMessage(msg)
	es.Entities = append(es.Entities, &protocol.Entity{
		ServiceName:   "com.example.shoppingcart.ShoppingCart", //fmt.Sprintf("%s.%s", fd.Package, fd.Service[0].Name),
		EntityType:    "cloudstate.eventsourced.EventSourced",
		PersistenceId: "ShoppingCartEntity",
	})
	log.Printf("%v", md)
	set.File = append(set.File, fd)
	if b, e := proto.Marshal(set); e == nil {
		es.Proto = b
	} else {
		panic("huiii")
	}

	// TODO: we should remember what we've added
	//for _, filename := range fd.Dependency {
	//	unpackAndAdd(filename, set)
	//}
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
	if descriptorProto, e := unpackFile(proto.FileDescriptor(filename)); e != nil {
		panic(e)
	} else {
		set.File = append(set.File, descriptorProto)
	}
}

func (edr *EntityDiscoveryResponder) Discover(c context.Context, pi *protocol.ProxyInfo) (*protocol.EntitySpec, error) {
	log.Printf("Discover: %v\n", pi)
	log.Printf("Received discovery call from sidecar [%s %s] supporting CloudState %v.%v\n",
		pi.ProxyName,
		pi.ProxyVersion,
		pi.ProtocolMajorVersion,
		pi.ProtocolMinorVersion,
	)
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
	// TODO: resolve dependent proto files by the message itself
	unpackAndAdd("google/protobuf/empty.proto", &set)
	unpackAndAdd("google/protobuf/any.proto", &set)
	unpackAndAdd("google/protobuf/descriptor.proto", &set)
	unpackAndAdd("google/api/annotations.proto", &set)
	unpackAndAdd("google/api/http.proto", &set)
	unpackAndAdd("cloudstate/event_sourced.proto", &set)
	unpackAndAdd("cloudstate/entity.proto", &set)
	unpackAndAdd("cloudstate/entity_key.proto", &set)

	if protoBytes, e := proto.Marshal(&set); e == nil {
		es.Proto = protoBytes
	} else {
		log.Fatalf("unable to Marshal FileDescriptorSet")
	}
	log.Printf("Responding with: %v\n", es.ServiceInfo)
	return &es, nil
}

func (edr *EntityDiscoveryResponder) ReportError(c context.Context, ufe *protocol.UserFunctionError) (*empty.Empty, error) {
	log.Printf("ReportError: %v\n", ufe)
	return &empty.Empty{}, nil
}

type CartServer struct {
	cart map[string]shoppingcart.LineItem
}

func NewCartServer() *CartServer {
	return &CartServer{cart: make(map[string]shoppingcart.LineItem, 0)}
}

func (s *CartServer) AddItem(c context.Context, li *shoppingcart.AddLineItem) (*empty.Empty, error) {
	log.Printf("  AddItem: %v\n", li)
	if item, exists := s.cart[li.GetProductId()]; exists {
		s.cart[li.GetProductId()] = shoppingcart.LineItem{
			ProductId: li.GetProductId(),
			Name:      li.GetName(),
			Quantity:  item.Quantity + li.GetQuantity(),
		}
	} else {
		s.cart[li.GetProductId()] = shoppingcart.LineItem{
			ProductId: li.GetProductId(),
			Name:      li.GetName(),
			Quantity:  li.GetQuantity(),
		}
	}
	return &empty.Empty{}, nil
}

func (s *CartServer) RemoveItem(c context.Context, li *shoppingcart.RemoveLineItem) (*empty.Empty, error) {
	log.Printf("  RemoveItem: %v\n", li)
	delete(s.cart, li.GetProductId())
	return &empty.Empty{}, nil
}

func (s *CartServer) GetCart(c context.Context, sc *shoppingcart.GetShoppingCart) (*shoppingcart.Cart, error) {
	log.Printf("GetCart: %v\n", sc)
	return &shoppingcart.Cart{}, nil
}

type EventSourcedHandler struct {
	entities map[string]*EventSourcedEntityContext
}

type EventSourcedEntityContext struct {
	esi protocol.EventSourcedInit
	c   CartServer
}

func (esh *EventSourcedHandler) Handle(server protocol.EventSourced_HandleServer) error {
	for {
		streamIn, err := server.Recv()
		if err == io.EOF {
			return nil
		}
		if i := streamIn.GetInit(); i != nil {
			log.Printf("received init: %v\n", i)
			// received init: service_name:"com.example.shoppingcart.ShoppingCart" entity_id:"testuser:1"
			//i.GetSnapshot().Snapshot
			//serviceName := i.GetServiceName()
			eid := i.GetEntityId()
			log.Printf("  register init with entityId: %v", eid)
			if _, present := esh.entities[eid]; present {
				if err := server.Send(&protocol.EventSourcedStreamOut{
					Message: &protocol.EventSourcedStreamOut_Failure{
						Failure: &protocol.Failure{
							CommandId:   0, // we have no command id
							Description: "entity already initialized",
						}}});
					err != nil {
					log.Fatalf("unable to server.Send")
				}
			} else {
				esh.entities[eid] = &EventSourcedEntityContext{
					esi: *i,
					c:   *NewCartServer(),
				}
			}
		}
		if cmd := streamIn.GetCommand(); cmd != nil {
			log.Printf("received command: %v\n", cmd)

			//entityId := c.GetEntityId()
			//i := esh.entities[entityId]

			//serviceName := i.GetServiceName()
			//serviceMethodName := c.GetName()
			//typeUrl := c.GetPayload().GetTypeUrl()
			//
			// we are instructed to call <serviceMethodName> from the service <serviceName> using the
			// serviceMethodName's payload, in this case

			if cmd.GetName() == "RemoveItem" {
				removeLineItem := shoppingcart.RemoveLineItem{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &removeLineItem) != nil {
					log.Fatalf("failed to unmarshal AddLineItem")
				}

				ctx := esh.entities[cmd.GetEntityId()]
				reply, _ := ctx.c.RemoveItem(server.Context(), &removeLineItem)
				marshal, err := proto.Marshal(reply)
				if err != nil {
					log.Fatalf("unable to Marshal")
				}
				log.Printf("  reply with: %v\n", marshal)
				_ = server.Send(&protocol.EventSourcedStreamOut{
					Message: &protocol.EventSourcedStreamOut_Reply{
						Reply: &protocol.EventSourcedReply{
							CommandId: cmd.GetId(),
							ClientAction: &protocol.ClientAction{
								Action: &protocol.ClientAction_Reply{
									Reply: &protocol.Reply{
										Payload: &any.Any{
											TypeUrl: "type.googleapis.com/google.protobuf.Empty",
											Value:   marshal,
										},
									},
								},
							},
						},
					},
				})
			}

			if cmd.GetName() == "AddItem" {
				addLineItem := shoppingcart.AddLineItem{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &addLineItem) != nil {
					log.Fatalf("failed to unmarshal AddLineItem")
				}
				ctx := esh.entities[cmd.GetEntityId()]
				reply, _ := ctx.c.AddItem(server.Context(), &addLineItem)
				marshal, err := proto.Marshal(reply)
				if err != nil {
					log.Fatalf("unable to Marshal")
				}
				log.Printf("  reply with: %v\n", marshal)
				_ = server.Send(&protocol.EventSourcedStreamOut{
					Message: &protocol.EventSourcedStreamOut_Reply{
						Reply: &protocol.EventSourcedReply{
							CommandId: cmd.GetId(),
							ClientAction: &protocol.ClientAction{
								Action: &protocol.ClientAction_Reply{
									Reply: &protocol.Reply{
										Payload: &any.Any{
											TypeUrl: "type.googleapis.com/google.protobuf.Empty",
											Value:   marshal,
										},
									},
								},
							},
						},
					},
				})
			}

			if cmd.GetName() == "GetCart" {
				getShoppingCart := shoppingcart.GetShoppingCart{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &getShoppingCart) != nil {
					log.Fatalf("failed to unmarshal GetShoppingCart")
				}
				ctx := esh.entities[cmd.GetEntityId()]
				cart := shoppingcart.Cart{
				}
				for _, c := range ctx.c.cart {
					cart.Items = append(cart.Items, &shoppingcart.LineItem{
						ProductId: c.GetProductId(),
						Name:      c.GetName(),
						Quantity:  c.GetQuantity(),
					})
				}
				marshal, err := proto.Marshal(&cart);
				if err != nil {
					log.Fatalf("unable to Marshal")
				}
				log.Printf("  reply with: %v\n", cart)

				if (server.Send(&protocol.EventSourcedStreamOut{
					Message: &protocol.EventSourcedStreamOut_Reply{
						Reply: &protocol.EventSourcedReply{
							CommandId: cmd.GetId(),
							ClientAction: &protocol.ClientAction{
								Action: &protocol.ClientAction_Reply{
									Reply: &protocol.Reply{
										Payload: &any.Any{
											TypeUrl: "type.googleapis.com/com.example.shoppingcart.Cart",
											Value:   marshal,
										},
									},
								},
							},
						},
					},
				}) != nil) {
					log.Fatalf("unable to server.Send")
				}
			}
		}
		if c := streamIn.GetEvent(); c != nil {
			log.Printf("received event: %v\n", c)
		}
	}
	// https://grpc.io/docs/tutorials/basic/go/
	return nil;
}

func main() {
	//lis, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%s", os.Getenv("PORT")))
	lis, err := net.Listen("tcp",
		fmt.Sprintf("%s:%s", os.Getenv("HOST"), os.Getenv("PORT")),
	)
	if err != nil {
		log.Fatalf("failed to listen: %v\n", err)
	}
	grpcServer := grpc.NewServer()
	protocol.RegisterEntityDiscoveryServer(grpcServer, &EntityDiscoveryResponder{})
	log.Println("RegisterEntityDiscoveryServer")
	protocol.RegisterEventSourcedServer(grpcServer, &EventSourcedHandler{
		entities: make(map[string]*EventSourcedEntityContext, 0),
	})
	log.Println("RegisterEventSourcedServer")
	shoppingcart.RegisterShoppingCartServer(grpcServer, &CartServer{})
	log.Println("RegisterShoppingCartServer")
	if e := grpcServer.Serve(lis); e != nil {
		log.Fatalf("failed to grpcServer.Serve for: %v", lis)
	}
}
