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

import "log"

type EventEmitter interface {
	Emit(event interface{})
	Events() []interface{}
	Clear()
}

func NewEmitter() *eventEmitter {
	return &eventEmitter{
		events: make([]interface{}, 0)[:],
	}
}

type eventEmitter struct {
	events []interface{}
}

func (e *eventEmitter) Emit(event interface{}) {
	log.Printf("emitted event: %v", event)
	e.events = append(e.events, event)
}

func (e *eventEmitter) Events() []interface{} {
	return e.events
}

func (e *eventEmitter) Clear() {
	e.events = make([]interface{}, 0)[:]
}

type EventHandler interface {
	Handle(event interface{}) (handled bool, err error)
}
