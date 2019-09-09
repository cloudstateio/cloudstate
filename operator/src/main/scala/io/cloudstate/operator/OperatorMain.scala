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

package io.cloudstate.operator

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, KillSwitch}
import com.typesafe.config.ConfigFactory
import skuber.{ConfigMap, Namespace}
import skuber.api.client.{EventType, WatchEvent}
import skuber.json.format._

import scala.collection.concurrent.TrieMap

object OperatorMain extends App {

  private val operatorNamespace =
    sys.env.getOrElse("NAMESPACE", sys.error("No NAMESPACE environment variable configured!"))
  private val configMapName = sys.env.getOrElse("CONFIG_MAP", "cloudstate-operator-config")

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()

  import system.dispatcher

  val client = skuber.k8sInit

  private val runners = List(
    new OperatorRunner(client, new StatefulStoreOperatorFactory()),
    new OperatorRunner(client, new StatefulServiceOperatorFactory())
  )

  @volatile private var currentConfig: Option[OperatorConfig] = None
  @volatile private var currentlyRunning: Option[AutoCloseable] = None

  def maybeRestart(configMap: ConfigMap): Unit = {
    val configString = configMap.data.getOrElse("config", "")
    val config = ConfigFactory
      .parseString(configString)
      .withFallback(ConfigFactory.defaultReference())
    val opConfig = OperatorConfig(config)
    if (!currentConfig.contains(opConfig)) {
      currentConfig = Some(opConfig)
      println("Received new config:")
      println(configString)
      currentlyRunning match {
        case Some(closeable) =>
          println("Restarting...")
          closeable.close()
        case None =>
          println("Starting...")
      }

      if (opConfig.namespaces.contains("*")) {
        println("Watching all namespaces...")
        currentlyRunning = Some(watchAllNamespaces(opConfig))
      } else {
        println("Watching configured namespaces: " + opConfig.namespaces.mkString(", "))
        currentlyRunning = Some(watchConfiguredNamespaces(opConfig))
      }
    }
  }

  Watcher.watchSingle[ConfigMap](
    client.usingNamespace(operatorNamespace),
    configMapName,
    Flow[WatchEvent[ConfigMap]].map {
      case WatchEvent(EventType.ADDED, map) =>
        maybeRestart(map)
      case WatchEvent(EventType.MODIFIED, map) =>
        maybeRestart(map)
      case WatchEvent(EventType.DELETED, _) =>
        println("Config map deleted, stopping watchers")
        currentlyRunning.foreach(_.close())
        currentlyRunning = None
        currentConfig = None
      case _ =>
    }
  )

  def watchConfiguredNamespaces(config: OperatorConfig): AutoCloseable = {
    val killSwitches = for {
      namespace <- config.namespaces
      runner <- runners
    } yield runner.start(namespace, config)

    () => killSwitches.foreach(_.shutdown())
  }

  def watchAllNamespaces(config: OperatorConfig): AutoCloseable = {
    val namespaces = TrieMap.empty[String, List[KillSwitch]]

    def watch(namespace: Namespace): Unit =
      namespaces.put(namespace.name, runners.map(_.start(namespace.name, config)))

    def unwatch(namespace: Namespace): Unit =
      namespaces.get(namespace.name).foreach { killSwitches =>
        killSwitches.foreach(_.shutdown())
        namespaces.remove(namespace.name)
      }

    val killSwitch = Watcher.watch[Namespace](
      client,
      Flow[WatchEvent[Namespace]].map {
        case WatchEvent(EventType.ADDED, namespace) if !isWatchingDisabled(namespace) =>
          println(s"Watching new namespace ${namespace.name}")
          watch(namespace)
        case WatchEvent(EventType.MODIFIED, namespace)
            if isWatchingDisabled(namespace) && namespaces.contains(namespace.name) =>
          println(
            s"Namespace ${namespace.name} has had io.cloudstate/watch=disabled annotation added to it, stopping watcher."
          )
          unwatch(namespace)
        case WatchEvent(EventType.MODIFIED, namespace)
            if !isWatchingDisabled(namespace) && !namespaces.contains(namespace.name) =>
          println(s"Watching namespace ${namespace.name}")
          watch(namespace)
        case WatchEvent(EventType.DELETED, namespace) if namespaces.contains(namespace.name) =>
          println(s"Namespace ${namespace.name} has been deleted, stopping watcher.")
          unwatch(namespace)
        case _ =>
          ()
      }
    )

    () => {
      killSwitch.shutdown()
      // Wait a short while before stopping all the child watchers
      Thread.sleep(2000)
      namespaces.flatMap(_._2).foreach(_.shutdown())
    }
  }

  private def isWatchingDisabled(namespace: Namespace): Boolean =
    namespace.metadata.annotations.get("cloudstate.io/watch").contains("disabled")
}
