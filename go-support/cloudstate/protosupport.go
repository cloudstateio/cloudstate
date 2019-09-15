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
	"bytes"
	"compress/gzip"
	"errors"
	"github.com/golang/protobuf/proto"
	filedescr "github.com/golang/protobuf/protoc-gen-go/descriptor"
	"io/ioutil"
)

const protoAnyBase = "type.googleapis.com"

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
