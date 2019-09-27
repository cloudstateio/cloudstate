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
	"bytes"
	"cloudstate.io/gosupport/cloudstate/protocol"
	"context"
	_ "google.golang.org/genproto/googleapis/api/annotations"
	"log"
	"os"
	"strings"
	"testing"
)

func TestNewCloudState(t *testing.T) {
	cloudState := NewCloudState(&Options{})
	si := cloudState.server.GetServiceInfo()
	if si == nil {
		t.Fail()
	}
}

func TestEntityDiscoveryResponderDiscover(t *testing.T) {
	responder := NewEntityDiscoveryResponder(&Options{
		ServiceName:    "service.one",
		ServiceVersion: "0.0.1",
	})
	info := &protocol.ProxyInfo{
		ProtocolMajorVersion: 0,
		ProtocolMinorVersion: 0,
		ProxyName:            "test-proxy",
		ProxyVersion:         "9.8.7",
		SupportedEntityTypes: nil,
	}
	spec, err := responder.Discover(context.Background(), info)
	if err != nil {
		t.Errorf("responder.Discover returned err: %v", err)
	}
	if spec == nil {
		t.Errorf("no EntitySpec returned by responder.Discover")
	}

	if spec.ServiceInfo.ServiceName != "service.one" {
		t.Errorf("spec.ServiceInfo.ServiceName != 'service.one' but is: %s", spec.ServiceInfo.ServiceName)
	}
	if spec.ServiceInfo.ServiceVersion != "0.0.1" {
		t.Errorf("spec.ServiceInfo.ServiceVersion != '0.0.1' but is: %s", spec.ServiceInfo.ServiceVersion)
	}
	if !strings.HasPrefix(spec.ServiceInfo.ServiceRuntime, "go") {
		t.Errorf("spec.ServiceInfo.ServiceRuntime does not start with prefix: go")
	}
	if spec.ServiceInfo.SupportLibraryName != "cloudstate-go-support" {
		t.Errorf("spec.ServiceInfo.SupportLibraryName != 'cloudstate-go-support'")
	}
}

func captureOutput(f func()) string {
	var buf bytes.Buffer
	log.SetOutput(&buf)
	defer log.SetOutput(os.Stderr)
	f()
	return buf.String()
}

func TestEntityDiscoveryResponderReportError(t *testing.T) {
	responder := NewEntityDiscoveryResponder(&Options{
		ServiceName:    "service.one",
		ServiceVersion: "0.0.1",
	})
	output := captureOutput(func() {
		empty, err := responder.ReportError(context.Background(), &protocol.UserFunctionError{
			Message: "unable to do XYZ",
		})
		if err != nil || empty == nil {
			t.Errorf("responder.ReportError failed with err: %v, empty: %v", err, empty)
		}
	})
	if !strings.Contains(output, "unable to do XYZ") {
		t.Errorf("'unable to do XYZ' not found in output: %s", output)
	}
}
