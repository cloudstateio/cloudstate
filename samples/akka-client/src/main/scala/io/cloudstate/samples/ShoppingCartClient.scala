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

package io.cloudstate.samples

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.ActorMaterializer
import com.example.shoppingcart.shoppingcart._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

object ShoppingCartClient {
  def main(args: Array[String]): Unit = {

    val client = new ShoppingCartClient("localhost", 9000, None)

    val userId = "viktor"
    val productId = "1337"
    val productName = "h4x0r"

    try {
      println(client.getCart(userId))
      for (_ <- 1 to 8) {
        client.addItem(userId, productId, productName, 1)
      }
      println(client.getCart(userId))
      client.removeItem(userId, productId)
      println(client.getCart(userId))
    } catch {
      case NonFatal(e) => e.printStackTrace()
    } finally {
      try {
        client.shutdown()
      } finally {
        System.exit(0)
      }
    }
  }
}

/**
 * Designed for use in the REPL, run sbt console and then new io.cloudstate.samples.ShoppingCartClient("localhost", 9000)
 * @param hostname
 * @param port
 */
class ShoppingCartClient(hostname: String, port: Int, hostnameOverride: Option[String], sys: ActorSystem) {
  def this(hostname: String, port: Int, hostnameOverride: Option[String] = None) =
    this(hostname, port, hostnameOverride, ActorSystem())
  private implicit val system = sys
  private implicit val materializer = ActorMaterializer()

  import sys.dispatcher

  val settings = {
    val s = GrpcClientSettings.connectToServiceAt(hostname, port).withTls(false)
    hostnameOverride.fold(s)(host => s.withChannelBuilderOverrides(_.overrideAuthority(host)))
  }
  println(s"Connecting to $hostname:$port")
  val service = com.example.shoppingcart.shoppingcart.ShoppingCartClient(settings)

  def shutdown(): Unit = {
    await(service.close())
    await(system.terminate())
  }

  def await[T](future: Future[T]): T = Await.result(future, 10.seconds)

  def getCart(userId: String) = await(service.getCart(GetShoppingCart(userId)))
  def addItem(userId: String, productId: String, name: String, quantity: Int) =
    await(service.addItem(AddLineItem(userId, productId, name, quantity)))
  def removeItem(userId: String, productId: String) = await(service.removeItem(RemoveLineItem(userId, productId)))
}
