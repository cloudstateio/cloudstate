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

package io.cloudstate.testkit

object Sockets {
  // We were using akka.testkit.SocketUtil.temporaryLocalPort but that is broken
  // in akka 2.6.9 (and will be fixed by https://github.com/akka/akka/pull/29607).
  def temporaryLocalPort(): Int = {
    val address = new java.net.InetSocketAddress("localhost", 0)
    val socket = java.nio.channels.ServerSocketChannel.open().socket()
    try {
      socket.bind(address)
      socket.getLocalPort
    } finally socket.close()
  }
}
