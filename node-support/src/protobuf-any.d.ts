/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import * as Long from "long";
import * as protobufs from "../proto/protobuf-bundle";

export default class AnySupport {

    static serialize(obj: (object | Long | string | number | boolean), allowPrimitives: boolean, fallbackToJson: boolean,
                     requireJsonType: boolean): protobufs.google.protobuf.Any

    static toComparable(obj: (object | Long | string | number)): (string | number | boolean)

    deserialize(any: protobufs.google.protobuf.Any): (object | Long | string | number | boolean)
}