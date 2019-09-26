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

type subscription func(event interface{}) error

type EventEmitter interface {
	Emit(event interface{})
	Subscribe(subscription)
	Events() []interface{}
	Clear()
}

func NewEmitter() *eventEmitter {
	return &eventEmitter{
		events:        make([]interface{}, 0),
		subscriptions: make([]subscription, 0),
	}
}

type eventEmitter struct {
	events        []interface{}
	subscriptions []subscription
}

// Emit will immediately invoke the associated event handler for that event -
// this both validates that the event can be applied to the current state, as well as
// updates the state so that subsequent processing in the command handler can use it.
func (e *eventEmitter) Emit(event interface{}) {
	e.events = append(e.events, event)
	for _, subs := range e.subscriptions {
		_ = subs(event) // TODO: what to do with the error
	}
}

func (e *eventEmitter) Events() []interface{} {
	return e.events
}

func (e *eventEmitter) Subscribe(subs subscription) {
	e.subscriptions = append(e.subscriptions[:0], subs) // TODO: support more than
}

func (e *eventEmitter) Clear() {
	e.events = make([]interface{}, 0)
}

type EventHandler interface {
	HandleEvent(event interface{}) (handled bool, err error)
}

type Snapshotter interface {
	Snapshot() (snapshot interface{}, err error)
}

type SnapshotHandler interface {
	HandleSnapshot(snapshot interface{}) (handled bool, err error)
}
