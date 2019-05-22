package com.example

import akka.actor.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.stream.ActorMaterializer
import com.example.shoppingcart.{AddLineItem, GetShoppingCart, RemoveLineItem, ShoppingCartClient}

import scala.util.{Failure, Random, Success}

object GenerateLoad extends App {

  val products = Seq(
    "1" -> "Bread",
    "2" -> "Milk",
    "3" -> "Apple",
    "4" -> "Orange",
    "5" -> "Flour",
    "6" -> "Steak",
    "7" -> "Chicken",
    "8" -> "Coke"
  )

  val users = (1 to 10).toSeq
  val NumClients = 20

  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val settings = GrpcClientSettings.connectToServiceAt("35.197.173.27", 80).withTls(false)
      .withChannelBuilderOverrides(_.overrideAuthority("shopping-cart.default.example.com"))

  def makeRandomRequest(client: ShoppingCartClient) = {
    val user = "user" + users(Random.nextInt(users.size))
    client.getCart(GetShoppingCart(user)).flatMap { cart =>
      println("Got cart for " + user + " which has " + cart.items.size + " items.")
      val add = cart.items.isEmpty || Random.nextDouble() > 0.1
      if (add) {
        val (id, name) = products(Random.nextInt(products.size))
        client.addItem(AddLineItem(user, id, name, Random.nextInt(10) + 1))
      } else {
        client.removeItem(RemoveLineItem(user, cart.items(Random.nextInt(cart.items.size)).productId))
      }
    }
  }

  def run(client: ShoppingCartClient): Unit = {
    makeRandomRequest(client)
      .onComplete {
        case Success(_) =>
          run(client)
        case Failure(exception) =>
          println("Error: " + exception)
          run(client)
      }
  }

  Seq(1 to 20).foreach { _ =>
    val service = ShoppingCartClient(settings)
    run(service)
  }

}
