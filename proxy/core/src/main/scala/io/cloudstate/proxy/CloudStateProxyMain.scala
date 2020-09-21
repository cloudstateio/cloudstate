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

package io.cloudstate.proxy

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.config.Config
import akka.actor.{ActorSelection, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.SystemMaterializer
import io.cloudstate.proxy.telemetry.CloudstateTelemetry
import org.slf4j.LoggerFactory
import sun.misc.Signal

import scala.concurrent.Future
import scala.concurrent.duration._

final class HealthCheckReady(system: ActorSystem) extends (() => Future[Boolean]) {
  private[this] final val log = LoggerFactory.getLogger(getClass)
  private[this] final val timeoutMs =
    system.settings.config.getConfig("cloudstate.proxy").getDuration("ready-timeout").toMillis.millis
  private[this] final implicit val ec = system.dispatcher
  private[this] final val serverManager = system.actorSelection("/user/server-manager-supervisor/server-manager")
  private[this] final implicit val timeout = Timeout(timeoutMs)

  private[this] final def check(name: String, selection: ActorSelection, msg: Any) =
    selection
      .resolveOne()
      .flatMap(_ ? msg)
      .mapTo[Boolean]
      .recover {
        case e =>
          log.debug(s"Error performing $name readiness check", e)
          false
      }

  override final def apply(): Future[Boolean] =
    Future
      .sequence(
        Seq(
          check("server manager", serverManager, EntityDiscoveryManager.Ready)
        )
      )
      .map(_.reduce(_ && _))
}

final class HealthCheckLive(system: ActorSystem) extends (() => Future[Boolean]) {
  override final def apply(): Future[Boolean] =
    Future.successful(true)
}

object CloudStateProxyMain {
  final case class Configuration(
      devMode: Boolean,
      backoffMin: FiniteDuration,
      backoffMax: FiniteDuration,
      backoffRandomFactor: Double
  ) {
    validate()
    def this(config: Config) = {
      this(
        devMode = config.getBoolean("dev-mode-enabled"),
        backoffMin = config.getDuration("backoff.min").toMillis.millis,
        backoffMax = config.getDuration("backoff.max").toMillis.millis,
        backoffRandomFactor = config.getDouble("backoff.random-factor")
      )
    }

    private[this] final def validate(): Unit = {
      require(backoffMin >= Duration.Zero)
      require(backoffMax >= backoffMin)
      require(backoffRandomFactor >= 0d)
    }
  }

  private val isGraalVM = sys.props.get("org.graalvm.nativeimage.imagecode").contains("runtime")

  /**
   * Work around for https://github.com/oracle/graal/issues/1610.
   *
   * ThreadLocalRandom gets initialized with a static seed generator, from this generator all seeds for
   * each thread are generated, but this gets computed at build time when compiling a native image, which
   * means that you get the same sequence of seeds each time you run the native image, and one serious
   * consequence of this is that every cluster node ends up with the same UID, and that causes big problems.
   * We can't tell Graal not to initialize at build time because it's already loaded by Graal itself.
   * So, we have to reset that field ourselves.
   */
  private def initializeThreadLocalRandom(): Unit = {
    // MurmurHash3 64 bit mixer to give an even distribution of seeds:
    // https://github.com/aappleby/smhasher/wiki/MurmurHash3
    def mix64(z: Long): Long = {
      val z1 = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL
      val z2 = (z1 ^ (z1 >>> 33)) * 0xC4CEB9FE1A85EC53L
      z2 ^ (z2 >>> 33)
    }

    val seed = mix64(System.currentTimeMillis) ^ mix64(System.nanoTime)
    val field = classOf[ThreadLocalRandom].getDeclaredField("seeder")
    field.setAccessible(true)
    field.get(null).asInstanceOf[AtomicLong].set(seed)
  }

  final def main(args: Array[String]): Unit =
    start()

  final def start(): ActorSystem =
    start(None)

  final def start(config: Config): ActorSystem =
    start(Option(config))

  private def start(configuration: Option[Config]): ActorSystem = {
    // Must do this first, before anything uses ThreadLocalRandom
    if (isGraalVM) {
      initializeThreadLocalRandom()
    }

    implicit val system = configuration.fold(ActorSystem("cloudstate-proxy"))(c => ActorSystem("cloudstate-proxy", c))
    implicit val materializer = SystemMaterializer(system)
    import system.dispatcher

    val jvmName = sys.props.get("java.runtime.name").orElse(sys.props.get("java.vm.name")).getOrElse("")
    val jvmVersion = sys.props.get("java.runtime.version").orElse(sys.props.get("java.vm.version")).getOrElse("")
    system.log.info(s"Starting Cloudstate Proxy version [${BuildInfo.version}] running on [$jvmName $jvmVersion]")

    val c = system.settings.config.getConfig("cloudstate.proxy")
    val serverConfig = new EntityDiscoveryManager.Configuration(c)
    val appConfig = new CloudStateProxyMain.Configuration(c)

    val cluster = Cluster(system)

    if (isGraalVM) {
      system.log.info("Registering SIGTERM handler...")
      // By default, Graal/SubstrateVM doesn't register any signal handlers, which means shutdown
      // hooks don't get executed (so no graceful leaving of the cluster). Worse, if the process
      // is the entrypoint for a Docker container (ie, it has pid 1) then it won't respond to TERM
      // at all, because Linux does not implement the default TERM handling if pid is 1, the result
      // being that the process will be killed after the configured termination timeout. So, we
      // need to register a TERM signal handler.
      Signal.handle(new Signal("TERM"), _ => System.exit(0))

      // And may as well register INT (Ctrl+C) while we're at it
      Signal.handle(new Signal("INT"), _ => System.exit(0))
    }

    // Bootstrap the cluster
    if (appConfig.devMode) {
      // In development, we just have a cluster of one, so we join ourself.
      cluster.join(cluster.selfAddress)
    } else {
      // Start cluster bootstrap
      AkkaManagement(system).start()
      ClusterBootstrap(system).start()
    }

    CloudstateTelemetry(system).start()

    system.actorOf(
      BackoffSupervisor.props(
        BackoffOpts.onFailure(
          EntityDiscoveryManager.props(serverConfig),
          childName = "server-manager",
          minBackoff = appConfig.backoffMin,
          maxBackoff = appConfig.backoffMax,
          randomFactor = appConfig.backoffRandomFactor
        )
      ),
      "server-manager-supervisor"
    )

    system
  }
}
