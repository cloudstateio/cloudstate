package io.cloudstate.loadgenerator

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, CoordinatedShutdown, Props, Status, Timers}
import akka.grpc.GrpcClientSettings
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.example.shoppingcart.shoppingcart._
import com.google.protobuf.empty.Empty

import scala.concurrent.duration._
import scala.util.Random

object GenerateLoad extends App {

  val system = ActorSystem()

  val loadGenerator = system.actorOf(
    BackoffSupervisor.props(
      BackoffOpts
        .onFailure(
          childProps = Props[LoadGeneratorActor],
          childName = "load-generator",
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2d
        )
        .withReplyWhileStopped(Done)
    ),
    "load-generator-supervisor"
  )

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "stop-making-requests") { () =>
    import akka.pattern.ask
    implicit val timeout = Timeout(10.seconds)
    (loadGenerator ? LoadGeneratorActor.Stop).mapTo[Done]
  }
}

object LoadGeneratorActor {
  case object Tick
  case object Report
  case object Stop

  val nanos = 1000 * 1000 * 1000
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
  val numUsers = sys.env.getOrElse("NUM_USERS", "100").toInt
  // The number of gRPC clients to instantiate. This controls how many connections the requests will be fanned out over.
  val numClients = sys.env.getOrElse("NUM_CLIENTS", "5").toInt
  val serviceName = sys.env.getOrElse("SHOPPING_CART_SERVICE", "localhost")
  val servicePort = sys.env.getOrElse("SHOPPING_CART_SERVICE_PORT", "9000").toInt
  // The ratio of read operations to write operations. A value of 0.9 means 90% of the operations will be read
  // operations.
  val readWriteRequestRatio = sys.env.getOrElse("READ_WRITE_REQUEST_RATIO", "0.9").toDouble
  require(readWriteRequestRatio >= 0.0 && readWriteRequestRatio <= 1.0)

  // The maximum amount of time that responses can lag behind requests before we throttle making requests. If this is
  // 2 seconds, and our request rate is 200 requests a second, then this means we can have at most 400 requests
  // outstanding before we stop making more requests
  val maxResponseLag = sys.env.getOrElse("MAX_RESPONSE_LAG_MS", "400").toInt.millis

  // How often to tick to send requests.
  val tickInterval = sys.env.getOrElse("TICK_INTERVAL_MS", "20").toInt.millis
  // The desired request rate. If this doesn't result in a whole number of requests per tick, the load generator will
  // randomly round up or down, weighted to the fractional portion of the number of requests per tick, to attempt to
  // achieve this rate on average.
  val requestRate = sys.env.getOrElse("REQUEST_RATE_PER_S", "1000").toInt
  val requestsPerTick: Double = tickInterval.div(1.second) * requestRate
  // The amount of time to spend ramping up. Ramp up is linear.
  val rampUpPeriodNanos = sys.env.getOrElse("RAMP_UP_S", "20").toInt.seconds.toNanos
  // How often the load generator should report it's current state (req/s, outstanding requests, etc) to stdout
  val reportInterval = sys.env.getOrElse("REPORT_INTERVAL_S", "5").toInt.seconds

  val maxOutstandingRequests = (maxResponseLag.toMillis.toDouble / 1000 * requestRate).toInt

  val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}

class LoadGeneratorActor extends Actor with Timers {

  import LoadGeneratorActor._
  import akka.pattern.pipe

  implicit val materializer = ActorMaterializer()
  implicit val system = context.system
  implicit val ec = context.dispatcher

  private val clients = {
    val settings = GrpcClientSettings
      .connectToServiceAt(serviceName, servicePort)
      .withTls(false)
      .withDeadline(1.minute)

    (1 to numClients).toList.map { _ =>
      ShoppingCartClient(settings)
    }
  }

  private var startupTime = System.nanoTime()
  private var outstandingRequests = 0
  private var lastReportNanos = System.nanoTime()
  private var requestsMadeSinceLastReport = 0
  private var responsesReceivedSinceLastReport = 0
  private var failuresSinceLastReport = 0

  private var stoppingRef: ActorRef = _

  timers.startPeriodicTimer("report", Report, reportInterval)

  override def receive = starting

  override def postStop(): Unit =
    if (stoppingRef != null) {
      stoppingRef ! Done
    }

  override def preStart(): Unit =
    clients.head.getCart(GetShoppingCart("user1")) pipeTo self

  /**
   * Let's say that we want to achieve 13 requests per second, with a 250ms tick interval. To do this, we need to make
   * 3.25 requests per tick. But you can't make 0.25 requests. If we round this up or down, we're going to end up with
   * a requests per second that is greater or less than our desired rate. We could track the rate across ticks, but
   * there's no simple way to do that, especially during warmup, and taking back off due to response lag into
   * consideration. Instead, we round 3.25 up or down randomly, weighted according to its decimal part, so on average,
   * 75% of the time we round down, 25% we round up, and therefore end up with an average of 13 requests a second, as
   * desired.
   */
  private def roundRandomWeighted(d: Double): Int = {
    val floor = d.floor
    val remainder = d - floor
    if (Random.nextDouble() > remainder) floor.toInt
    else floor.toInt + 1
  }

  private def requestsToMake(): Int = {
    val timeSinceStartup = System.nanoTime() - startupTime
    val thisTick = if (timeSinceStartup > rampUpPeriodNanos) {
      requestsPerTick
    } else {
      (timeSinceStartup.toDouble / rampUpPeriodNanos) * requestsPerTick
    }

    Math.min(roundRandomWeighted(thisTick), maxOutstandingRequests - outstandingRequests)
  }

  // To start, we send a single request, and we don't start generating load until our first request is successful.
  // This is so that the autoscaler doesn't decide to scale up to a ridiculous number when scaled to zero.
  def starting: Receive = {
    case _: Cart =>
      timers.startPeriodicTimer("tick", Tick, tickInterval)
      lastReportNanos = System.nanoTime()
      startupTime = System.nanoTime()
      context become (running orElse report)
    case Status.Failure(err) =>
      throw err
    case Report =>
      println("%s Report: waiting for first request to succeed".format(dateTimeFormatter.format(ZonedDateTime.now())))
    case Stop =>
      stoppingRef = sender()
      context stop self
  }

  def running: Receive = {
    case Tick =>
      for (i <- 0 until requestsToMake()) {
        val client = clients(i % numClients)

        val user = "user" + (Random.nextInt(numUsers) + 1)
        Random.nextDouble() match {
          case read if read < readWriteRequestRatio =>
            client.getCart(GetShoppingCart(user)) pipeTo self
          case _ =>
            val (id, name) = products(Random.nextInt(products.size))
            client.addItem(AddLineItem(user, id, name, Random.nextInt(10) + 1)) pipeTo self
        }
        outstandingRequests += 1
        requestsMadeSinceLastReport += 1
      }

    case _: Cart | _: Empty =>
      responsesReceivedSinceLastReport += 1
      outstandingRequests -= 1

    case Status.Failure(err) =>
      err.printStackTrace()
      outstandingRequests -= 1
      failuresSinceLastReport += 1

    case Stop =>
      stoppingRef = sender()
      timers.cancel("tick")
      if (outstandingRequests == 0) {
        context stop self
      } else {
        context become (stopping orElse report)
      }
  }

  def report: Receive = {
    case Report =>
      val reportTime = System.nanoTime()
      val reportInterval = reportTime - lastReportNanos
      val requestsASecond = requestsMadeSinceLastReport.toDouble / reportInterval * nanos
      val responsesASecond = responsesReceivedSinceLastReport.toDouble / reportInterval * nanos
      val failuresASecond = failuresSinceLastReport.toDouble / reportInterval * nanos

      println(
        "%s Report: %4.0f req/s %4.0f success/s %4.0f failure/s with %d outstanding requests".format(
          dateTimeFormatter.format(ZonedDateTime.now()),
          requestsASecond,
          responsesASecond,
          failuresASecond,
          outstandingRequests
        )
      )

      lastReportNanos = reportTime
      requestsMadeSinceLastReport = 0
      responsesReceivedSinceLastReport = 0
      failuresSinceLastReport = 0
  }

  def stopping: Receive = {
    case Tick =>
    // Ignore

    case _: Cart | _: Empty =>
      responsesReceivedSinceLastReport += 1
      outstandingRequests -= 1
      if (outstandingRequests == 0) {
        context stop self
      }

    case Status.Failure(err) =>
      err.printStackTrace()
      outstandingRequests -= 1
      failuresSinceLastReport += 1
      if (outstandingRequests == 0) {
        context stop self
      }
  }
}
