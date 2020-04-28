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

// Package main implements an event sourced entity shopping cart example
package main

import (
	"context"
	"errors"
	"fmt"
	"log"

	"github.com/cloudstateio/go-support/cloudstate"
	"github.com/cloudstateio/go-support/tck/shoppingcart"
	domain "github.com/cloudstateio/go-support/tck/shoppingcart/persistence"
	"github.com/golang/protobuf/ptypes/empty"
)

// main creates a CloudState instance and registers the ShoppingCart
// as a event sourced entity.
//#shopping-cart-main
func main() {
	server, err := cloudstate.New(cloudstate.Config{
		ServiceName:    "shopping-cart",
		ServiceVersion: "0.1.0",
	})
//#register
	err = server.RegisterEventSourcedEntity(
		&cloudstate.EventSourcedEntity{
			ServiceName:   "com.example.shoppingcart.ShoppingCart",
			PersistenceID: "ShoppingCart",
			EntityFunc:    NewShoppingCart,
		},
		cloudstate.DescriptorConfig{
			Service: "shoppingcart/shoppingcart.proto",
		}.AddDomainDescriptor("domain.proto"),
	)
//#register
	if err != nil {
		log.Fatalf("CloudState failed to register entity: %v", err)
	}
	err = server.Run()
	if err != nil {
		log.Fatalf("CloudState failed to run: %v", err)
	}
}
//#shopping-cart-main

// A Cloudstate event sourced entity.
//#entity-type
//#compose-entity
type ShoppingCart struct {
	// our domain object
	//#entity-state
	cart []*domain.LineItem
	//#entity-state
	// as an Emitter we can emit events
	cloudstate.EventEmitter
}
//#compose-entity
//#entity-type

// NewShoppingCart returns a new and initialized instance of the ShoppingCart entity.
//#constructing
func NewShoppingCart() cloudstate.Entity {
	return &ShoppingCart{
		cart:         make([]*domain.LineItem, 0),
		EventEmitter: cloudstate.NewEmitter(),
	}
}
//#constructing

// ItemAdded is a event handler function for the ItemAdded event.
//#item-added
func (sc *ShoppingCart) ItemAdded(added *domain.ItemAdded) error {
	if item, _ := sc.find(added.Item.ProductId); item != nil {
		item.Quantity += added.Item.Quantity
	} else {
		sc.cart = append(sc.cart, &domain.LineItem{
			ProductId: added.Item.ProductId,
			Name:      added.Item.Name,
			Quantity:  added.Item.Quantity,
		})
	}
	return nil
}
//#item-added

// ItemRemoved is a event handler function for the ItemRemoved event.
func (sc *ShoppingCart) ItemRemoved(removed *domain.ItemRemoved) error {
	if !sc.remove(removed.ProductId) {
		// this should never happen
		return errors.New("unable to remove product")
	}
	return nil
}

// Handle lets us handle events by ourselves.
//
// returns handle set to true if we have handled the event
// and any error that happened during the handling
//#handle-event
func (sc *ShoppingCart) HandleEvent(_ context.Context, event interface{}) (handled bool, err error) {
	switch e := event.(type) {
	case *domain.ItemAdded:
		return true, sc.ItemAdded(e)
	case *domain.ItemRemoved:
		return true, sc.ItemRemoved(e)
	default:
		return false, nil
	}
}
//#handle-event

// AddItem implements the AddItem command handling of the shopping cart service.
//#add-item
func (sc *ShoppingCart) AddItem(_ context.Context, li *shoppingcart.AddLineItem) (*empty.Empty, error) {
	if li.GetQuantity() <= 0 {
		return nil, fmt.Errorf("cannot add negative quantity of to item %s", li.GetProductId())
	}
	sc.Emit(&domain.ItemAdded{
		Item: &domain.LineItem{
			ProductId: li.ProductId,
			Name:      li.Name,
			Quantity:  li.Quantity,
		}})
	return &empty.Empty{}, nil
}
//#add-item

// RemoveItem implements the RemoveItem command handling of the shopping cart service.
func (sc *ShoppingCart) RemoveItem(_ context.Context, li *shoppingcart.RemoveLineItem) (*empty.Empty, error) {
	if item, _ := sc.find(li.GetProductId()); item == nil {
		return nil, fmt.Errorf("cannot remove item %s because it is not in the cart", li.GetProductId())
	}
	sc.Emit(&domain.ItemRemoved{ProductId: li.ProductId})
	return &empty.Empty{}, nil
}

// GetCart implements the GetCart command handling of the shopping cart service.
//#get-cart
func (sc *ShoppingCart) GetCart(_ context.Context, _ *shoppingcart.GetShoppingCart) (*shoppingcart.Cart, error) {
	cart := &shoppingcart.Cart{}
	for _, item := range sc.cart {
		cart.Items = append(cart.Items, &shoppingcart.LineItem{
			ProductId: item.ProductId,
			Name:      item.Name,
			Quantity:  item.Quantity,
		})
	}
	return cart, nil
}
//#get-cart

//#handle-command
func (sc *ShoppingCart) HandleCommand(ctx context.Context, command interface{}) (handled bool, reply interface{}, err error) {
	switch cmd := command.(type) {
	case *shoppingcart.GetShoppingCart:
		reply, err := sc.GetCart(ctx, cmd)
		return true, reply, err
	case *shoppingcart.RemoveLineItem:
		reply, err := sc.RemoveItem(ctx, cmd)
		return true, reply, err
	case *shoppingcart.AddLineItem:
		reply, err := sc.AddItem(ctx, cmd)
		return true, reply, err
	default:
		return false, reply, err
	}
}
//#handle-command

//#snapshotter
func (sc *ShoppingCart) Snapshot() (snapshot interface{}, err error) {
	return domain.Cart{
		Items: append(make([]*domain.LineItem, len(sc.cart)), sc.cart...),
	}, nil
}
//#snapshotter

//#handle-snapshot
func (sc *ShoppingCart) HandleSnapshot(snapshot interface{}) (handled bool, err error) {
	switch value := snapshot.(type) {
	case domain.Cart:
		sc.cart = append(sc.cart[:0], value.Items...)
		return true, nil
	default:
		return false, nil
	}
}
//#handle-snapshot

// find finds a product in the shopping cart by productId and returns it as a LineItem.
func (sc *ShoppingCart) find(productId string) (item *domain.LineItem, index int) {
	for i, item := range sc.cart {
		if productId == item.ProductId {
			return item, i
		}
	}
	return nil, 0
}

// remove removes a product from the shopping cart.
//
// A ok flag is returned to indicate that the product was present and removed.
func (sc *ShoppingCart) remove(productId string) (ok bool) {
	if item, i := sc.find(productId); item != nil {
		// remove and re-slice
		copy(sc.cart[i:], sc.cart[i+1:])
		sc.cart = sc.cart[:len(sc.cart)-1]
		return true
	} else {
		return false
	}
}

func init() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
}
