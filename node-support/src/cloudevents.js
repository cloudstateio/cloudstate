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

/**
 * CloudEvent data.
 *
 * @interface module:cloudstate.CloudEvent
 * @property {string} specversion The CloudEvent spec version
 */
function toCloudevent(metadata) {
  return {
    get specversion() {
      return metadata["ce-specversion"];
    },
    get id() {
      return metadata["ce-id"];
    },
    set id(id) {
      metadata["ce-id"] = id;
    },
    get source() {
      return metadata["ce-source"];
    },
    set source(source) {
      metadata["ce-source"] = source;
    },
    get type() {
      return metadata["ce-type"];
    },
    set type(type) {
      metadata["ce-type"] = type;
    },
    get datacontenttype() {
      return metadata["Content-Type"];
    },
    set datacontenttype(datacontenttype) {
      metadata["Content-Type"] = datacontentype;
    },
    get dataschema() {
      return metadata["ce-dataschema"];
    },
    set dataschema(dataschema) {
      metadata["ce-dataschema"] = dataschema;
    },
    get subject() {
      return metadata["ce-subject"];
    },
    set subject(subject) {
      metadata["ce-subject"] = subject;
    },
    get time() {
      return metadata["ce-time"];
    },
    set time(time) {
      metadata["ce-time"] = time;
    },
  };
}

module.exports = {
  toCloudevent
};