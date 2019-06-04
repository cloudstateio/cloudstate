package com.lightbend.statefulserverless.loadgenerator

import java.time.ZonedDateTime
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.grpc.GrpcClientSettings
import akka.stream.ActorMaterializer
import com.example.shoppingcart.{AddLineItem, GetShoppingCart, RemoveLineItem, ShoppingCartClient}

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

object GenerateLoad extends App{

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

  // Read params from the environment
  // The number of users to make requests for. This will equate to the number of shopping cart entities that will be
  // requested, a higher number will result in more cache misses.
  val numUsers = sys.env.getOrElse("NUM_USERS", "1000").toInt
  // The number of clients to use to make requests. This controls the number of concurrent requests being made.
  val numClients = sys.env.getOrElse("NUM_CLIENTS", "100").toInt
  val serviceName = sys.env.getOrElse("SHOPPING_CART_SERVICE", "shopping-cart.default.35.189.22.136.xip.io")
  val servicePort = sys.env.getOrElse("SHOPPING_CART_SERVICE_PORT", "80").toInt
  // The ratio of read operations to write operations. A value of 0.9 means 90% of the operations will be read
  // operations. Note that every write operation will be proceeded with a read operation as well.
  val readWriteRequestRatio = sys.env.getOrElse("READ_WRITE_REQUEST_RATIO", "0.9").toDouble
  require(readWriteRequestRatio >= 0.0 && readWriteRequestRatio <= 1.0)
  // The ratio of add operations to delete operations. A value of 0.9 means 90% of the write operations will be add
  // operations.
  val addDeleteRequestRatio = sys.env.getOrElse("ADD_DELETE_REQUEST_RATIO", "0.9").toDouble
  require(addDeleteRequestRatio >= 0.0 && addDeleteRequestRatio <= 1.0)
  val otherToDeleteRequestRatio = ((1.0d - readWriteRequestRatio) * addDeleteRequestRatio) + readWriteRequestRatio

  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val settings = GrpcClientSettings.connectToServiceAt(serviceName, servicePort)
    .withTls(false)
    .withDeadline(1.minute)

  def makeRandomRequest(client: ShoppingCartClient) = {
    val user = "user" + (Random.nextInt(numUsers) + 1)
    client.getCart(GetShoppingCart(user)).flatMap { cart =>
      Random.nextDouble() match {
        case read if read < readWriteRequestRatio =>
          Future.successful(())
        case delete if cart.items.nonEmpty && delete > otherToDeleteRequestRatio =>
          client.removeItem(RemoveLineItem(user, cart.items(Random.nextInt(cart.items.size)).productId))
        case _ =>
          val (id, name) = products(Random.nextInt(products.size))
          client.addItem(AddLineItem(user, id, name, Random.nextInt(10) + 1))
      }
    }
  }

  val stopped = new AtomicBoolean()
  val requestCount = new AtomicLong()

  val allFinished = Future.sequence(Seq(1 to numClients).map { _ =>
    val service = ShoppingCartClient(settings)
    val finished = Promise[Done]()

    def run(): Unit = {
      makeRandomRequest(service)
        .onComplete {
          case _ if stopped.get() =>
            finished.success(Done)
          case Success(_) =>
            val count = requestCount.incrementAndGet()
            if (count % 100 == 0) {
              println(s"${ZonedDateTime.now()} - Successful request count: $count")
            }
            run()
          case Failure(exception) =>
            println(s"${ZonedDateTime.now()} - Error invoking $serviceName: $exception")
            run()
        }
    }

    run()
    finished.future
  })

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "stop-making-requests") { () =>
    stopped.set(true)
    allFinished.map(_ => Done)
  }

}
