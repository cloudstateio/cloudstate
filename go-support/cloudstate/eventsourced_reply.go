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

import "cloudstate.io/gosupport/cloudstate/protocol"

func sendEventSourcedReply(reply *protocol.EventSourcedReply, server protocol.EventSourced_HandleServer) error {
	return server.Send(&protocol.EventSourcedStreamOut{
		Message: &protocol.EventSourcedStreamOut_Reply{
			Reply: reply,
		},
	})
}

func sendFailure(failure *protocol.Failure, server protocol.EventSourced_HandleServer) error {
	return server.Send(&protocol.EventSourcedStreamOut{
		Message: &protocol.EventSourcedStreamOut_Failure{
			Failure: failure,
		},
	})
}

func sendClientActionFailure(failure *protocol.Failure, server protocol.EventSourced_HandleServer) error {
	return server.Send(&protocol.EventSourcedStreamOut{
		Message: &protocol.EventSourcedStreamOut_Reply{
			Reply: &protocol.EventSourcedReply{
				CommandId: failure.CommandId,
				ClientAction: &protocol.ClientAction{
					Action: &protocol.ClientAction_Failure{
						Failure: failure,
					},
				},
			},
		},
	})
}
