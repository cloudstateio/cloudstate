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
	filedescr "github.com/golang/protobuf/protoc-gen-go/descriptor"
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

func (edr *EntityDiscoveryResponder) Discover(c context.Context, pi *protocol.ProxyInfo) (*protocol.EntitySpec, error) {
	log.Printf("Discover: %v\n", pi)
	log.Printf("Received discovery call from sidecar [%s w%s] supporting CloudState %v.%v\n",
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
	set := filedescr.FileDescriptorSet{
		File: make([]*filedescr.FileDescriptorProto, 0),
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
		return nil, errors.New("unable to Marshal FileDescriptorSet")
	}
	log.Printf("Responding with: %v\n", es.ServiceInfo)
	return &es, nil
}

func (edr *EntityDiscoveryResponder) ReportError(c context.Context, ufe *protocol.UserFunctionError) (*empty.Empty, error) {
	log.Printf("ReportError: %v\n", ufe)
	return &empty.Empty{}, nil
}

func appendTypeTo(msg descriptor.Message, es *protocol.EntitySpec, set *filedescr.FileDescriptorSet) error {
	fd, md := descriptor.ForMessage(msg)
	es.Entities = append(es.Entities, &protocol.Entity{
		ServiceName:   "com.example.shoppingcart.ShoppingCart", //fmt.Sprintf("%s.%s", fd.Package, fd.Service[0].Name),
		EntityType:    "cloudstate.eventsourced.EventSourced",
		PersistenceId: "ShoppingCartEntity"})
	log.Printf("%v", md)
	set.File = append(set.File, fd)
	if b, e := proto.Marshal(set); e == nil {
		es.Proto = b
	} else {
		return errors.New("unable to Marshal FileDescriptorSet")
	}

	// TODO: we should remember what we've added
	//for _, filename := range fd.Dependency {
	//	unpackAndAdd(filename, set)
	//}
	return nil
}

func unpackFile(gz []byte) (*filedescr.FileDescriptorProto, error) {
	r, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return nil, errors.New("failed to open gzip reader")
	}
	defer r.Close()
	b, err := ioutil.ReadAll(r)
	if err != nil {
		return nil, errors.New("failed to uncompress descriptor")
	}
	fd := new(filedescr.FileDescriptorProto)
	if err := proto.Unmarshal(b, fd); err != nil {
		return nil, errors.New("malformed FileDescriptorProto")
	}
	return fd, nil
}

func unpackAndAdd(filename string, set *filedescr.FileDescriptorSet) {
	if descriptorProto, e := unpackFile(proto.FileDescriptor(filename)); e != nil {
		panic(e)
	} else {
		set.File = append(set.File, descriptorProto)
	}
}

type ShoppingCart struct {
	shoppingcart.Cart
}

func NewShoppingCart() *ShoppingCart {
	return &ShoppingCart{
		Cart: shoppingcart.Cart{
			Items: make([]*shoppingcart.LineItem, 0)[:],
		},
	}
}

func (s *ShoppingCart) find(productId string) (item *shoppingcart.LineItem, index int) {
	for i, item := range s.Items {
		if productId == item.GetProductId() {
			return item, i
		}
	}
	return nil, 0
}

func (s *ShoppingCart) remove(productId string) (ok bool) {
	if item, i := s.find(productId); item != nil {
		// remove and re-slice
		copy(s.Items[i:], s.Items[i+1:])
		s.Items = s.Items[:len(s.Items)-1]
		return true
	} else {
		return false
	}
}

func (s *ShoppingCart) AddItem(c context.Context, li *shoppingcart.AddLineItem) (*empty.Empty, error) {
	log.Printf("  AddItem: %v\n", li)
	if li.GetQuantity() <= 0 {
		return nil, errors.New(fmt.Sprintf("Cannot add negative quantity of to item %s", li.GetProductId()))
	}

	if item, _ := s.find(li.GetProductId()); item != nil {
		item.Quantity += li.GetQuantity()
	} else {
		s.Items = append(s.Items, &shoppingcart.LineItem{
			ProductId: li.GetProductId(),
			Name:      li.GetName(),
			Quantity:  li.GetQuantity(),
		})
	}
	return &empty.Empty{}, nil
}

func (s *ShoppingCart) RemoveItem(c context.Context, li *shoppingcart.RemoveLineItem) (*empty.Empty, error) {
	log.Printf("  RemoveItem: %v\n", li)
	if removed := s.remove(li.GetProductId()); !removed {
		return nil, errors.New(fmt.Sprintf("Cannot remove item %s because it is not in the cart.", li.GetProductId()))
	}
	return &empty.Empty{}, nil
}

func (s *ShoppingCart) GetCart(c context.Context, sc *shoppingcart.GetShoppingCart) (*shoppingcart.Cart, error) {
	log.Printf("GetCart: %v\n", sc)
	cart := &shoppingcart.Cart{}
	for _, item := range s.Items {
		cart.Items = append(cart.Items, item)
	}
	return cart, nil
}

type EventSourcedHandler struct {
	entities map[string]*EventSourcedEntityContext
}

type EventSourcedEntityContext struct {
	esi  protocol.EventSourcedInit
	cart ShoppingCart
}

func (esh *EventSourcedHandler) Handle(server protocol.EventSourced_HandleServer) error {
	for {
		msg, err := server.Recv()
		if err == io.EOF {
			return nil
		}
		if i := msg.GetInit(); i != nil {
			log.Printf("received init: %v\n", i)
			// received init: service_name:"com.example.shoppingcart.ShoppingCart" entity_id:"testuser:1"
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
					esi:  *i,
					cart: *NewShoppingCart(),
				}
			}
		}
		if cmd := msg.GetCommand(); cmd != nil {
			log.Printf("received command: %v\n", cmd)
			//serviceName := cmd.GetServiceName()
			//serviceMethodName := cart.GetName()
			//typeUrl := cart.GetPayload().GetTypeUrl()
			//
			// we are instructed to call <serviceMethodName> from the service <serviceName> using the
			// serviceMethodName's payload, in this case
			if cmd.GetName() == "RemoveItem" {
				removeLineItem := shoppingcart.RemoveLineItem{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &removeLineItem) != nil {
					log.Fatalf("failed to unmarshal AddLineItem")
				}
				ctx := esh.entities[cmd.GetEntityId()]
				// TODO: we should infer here the call to RemoveItem by the cmd.GetServiceName()
				reply, err := ctx.cart.RemoveItem(server.Context(), &removeLineItem)
				if err != nil {
					// TCK says: FIXME Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
					//send := &protocol.EventSourcedStreamOut{
					//	Message: &protocol.EventSourcedStreamOut_Failure{
					//		Failure: &protocol.Failure{
					//			CommandId:   cmd.GetId(),
					//			Description: err.Error(),
					//		}},
					//}
					send := &protocol.EventSourcedStreamOut{
						Message: &protocol.EventSourcedStreamOut_Reply{
							Reply: &protocol.EventSourcedReply{
								CommandId: cmd.GetId(),
								ClientAction: &protocol.ClientAction{
									Action: &protocol.ClientAction_Failure{
										Failure: &protocol.Failure{
											CommandId:   cmd.GetId(),
											Description: err.Error(),
										},
									},
								},
								Events: []*any.Any{{
									TypeUrl: cmd.GetPayload().GetTypeUrl(),
									Value:   cmd.GetPayload().GetValue(),
								}},
							},
						},
					}
					log.Printf("  reply with: %v\n", send)
					_ = server.Send(send)
					continue
				}
				if payload, err := proto.Marshal(reply); err != nil {
					log.Fatalf("unable to Marshal")
				} else {
					// so here we should produce what we would
					// replay as an event. the java client example
					// uses a separate Domain proto to capture that
					// so here we produce an event from a command
					// => command(

					// a ctx for one specific entity holds the entity
					// and has to match the event with a eventhandler
					// so that it gets called

					// for now, we just persist the payload we've got for the command
					send := &protocol.EventSourcedStreamOut{
						Message: &protocol.EventSourcedStreamOut_Reply{
							Reply: &protocol.EventSourcedReply{
								CommandId: cmd.GetId(),
								ClientAction: &protocol.ClientAction{
									Action: &protocol.ClientAction_Reply{
										Reply: &protocol.Reply{
											Payload: &any.Any{
												TypeUrl: "type.googleapis.com/google.protobuf.Empty",
												Value:   payload,
											},
										},
									},
								},
								Events: []*any.Any{{
									TypeUrl: cmd.GetPayload().GetTypeUrl(),
									Value:   cmd.GetPayload().GetValue(),
								}},
							},
						},
					}
					log.Printf("  reply with: %v\n", send)
					_ = server.Send(send)
				}
			}
			if cmd.GetName() == "AddItem" {
				addLineItem := shoppingcart.AddLineItem{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &addLineItem) != nil {
					log.Fatalf("failed to unmarshal AddLineItem")
				}
				ctx := esh.entities[cmd.GetEntityId()]
				reply, err := ctx.cart.AddItem(server.Context(), &addLineItem)
				if err != nil {
					// TCK says: FIXME Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
					//send := &protocol.EventSourcedStreamOut{
					//	Message: &protocol.EventSourcedStreamOut_Failure{
					//		Failure: &protocol.Failure{
					//			CommandId:   cmd.GetId(),
					//			Description: err.Error(),
					//		}},
					//}
					send := &protocol.EventSourcedStreamOut{
						Message: &protocol.EventSourcedStreamOut_Reply{
							Reply: &protocol.EventSourcedReply{
								CommandId: cmd.GetId(),
								ClientAction: &protocol.ClientAction{
									Action: &protocol.ClientAction_Failure{
										Failure: &protocol.Failure{
											CommandId:   cmd.GetId(),
											Description: err.Error(),
										},
									},
								},
								Events: []*any.Any{{
									TypeUrl: cmd.GetPayload().GetTypeUrl(),
									Value:   cmd.GetPayload().GetValue(),
								}},
							},
						},
					}
					log.Printf("  reply with: %v\n", send)
					_ = server.Send(send)
					continue
				}
				marshal, err := proto.Marshal(reply)
				if err != nil {
					log.Fatalf("unable to Marshal")
				}
				send := &protocol.EventSourcedStreamOut{
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
							Events: []*any.Any{{
								TypeUrl: cmd.GetPayload().GetTypeUrl(),
								Value:   cmd.GetPayload().GetValue(),
							}},
						},
					},
				}
				log.Printf("  reply with: %v\n", send)
				_ = server.Send(send)
			}
			if cmd.GetName() == "GetCart" {
				getShoppingCart := shoppingcart.GetShoppingCart{}
				if proto.Unmarshal(cmd.GetPayload().GetValue(), &getShoppingCart) != nil {
					log.Fatalf("failed to unmarshal GetShoppingCart")
				}
				ctx := esh.entities[cmd.GetEntityId()]
				marshal, err := proto.Marshal(&ctx.cart.Cart);
				if err != nil {
					log.Fatalf("unable to Marshal")
				}
				send := &protocol.EventSourcedStreamOut{
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
				}
				log.Printf("  reply with: %v\n", send)
				if server.Send(send) != nil {
					log.Fatalf("unable to server.Send")
				}
			}
		}
		if c := msg.GetEvent(); c != nil {
			log.Printf("received event: %v\n", c)
		}
	}
	return nil
}

func main() {
	lis, err := net.Listen("tcp",
		fmt.Sprintf("%s:%s", os.Getenv("HOST"), os.Getenv("PORT")),
	)
	if err != nil {
		log.Fatalf("failed to listen: %v\n", err)
	}
	grpcServer := grpc.NewServer()
	protocol.RegisterEntityDiscoveryServer(grpcServer, &EntityDiscoveryResponder{})
	log.Println("RegisterEntityDiscoveryServer")
	protocol.RegisterEventSourcedServer(grpcServer,
		&EventSourcedHandler{
			entities: make(map[string]*EventSourcedEntityContext, 0),
		},
	)
	log.Println("RegisterEventSourcedServer")
	if e := grpcServer.Serve(lis); e != nil {
		log.Fatalf("failed to grpcServer.Serve for: %v", lis)
	}
}
