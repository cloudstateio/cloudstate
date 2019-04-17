package com.example

import akka.actor.{ActorSystem}
import akka.util.Timeout
import akka.stream.ActorMaterializer
import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding
import akka.grpc.GrpcClientSettings
import com.typesafe.config.Config
import com.example.shoppingcart._
import com.google.protobuf.empty.Empty

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object Client {
  def main(args: Array[String]): Unit = {
    implicit val system           = ActorSystem("client")
    implicit val materializer     = ActorMaterializer()
    implicit val executionContext = system.dispatcher
    val clientSettings            = GrpcClientSettings.connectToServiceAt("127.0.0.1", 9000).withTls(false)
    val client                    = ShoppingCartClient(clientSettings)

    val userId = "viktor"
    val productId = "1337"
    val productName = "h4x0r"

    val future = for {
      u <- Future.unit
      c <- client.getCart(GetCart(userId))
      val _ = { println(c) }
      i <- client.addItem(AddLineItem(userId, productId, productName, 1))
      r <- client.removeItem(RemoveLineItem(userId, productId))
    } yield u

    future
      .andThen {
        case Success(s) => println(s.toString)
        case Failure(t) => t.printStackTrace()
      }
      .andThen { case _ =>
        client.close()
        materializer.shutdown()
        system.terminate().andThen({
          case _ => scala.sys.exit(1)
        })
    }
  }
}