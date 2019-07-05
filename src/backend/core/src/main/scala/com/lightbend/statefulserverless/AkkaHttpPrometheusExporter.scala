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

package com.lightbend.statefulserverless

import java.io.OutputStreamWriter
import java.util

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import io.prometheus.client.CollectorRegistry
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import akka.util.ByteString
import io.prometheus.client.exporter.common.TextFormat

import scala.concurrent.Future

/**
  * Serves Prometheus metrics
  */
class AkkaHttpPrometheusExporter(metricsPort: Int, registry: CollectorRegistry = CollectorRegistry.defaultRegistry)(implicit system: ActorSystem, mat: Materializer) {

  private val PrometheusContentType = ContentType.parse(TextFormat.CONTENT_TYPE_004).right.get

  private def routes = get {
    (path("metrics") | pathSingleSlash) {
      encodeResponse {
        parameter(Symbol("name[]").*) { names =>
          complete {
            val namesSet = new util.HashSet[String]()
            names.foreach(namesSet.add)
            val builder = ByteString.newBuilder
            val writer = new OutputStreamWriter(builder.asOutputStream)
            TextFormat.write004(writer, registry.filteredMetricFamilySamples(namesSet))
            // Very important to flush the writer before we build the byte string!
            writer.flush()
            HttpEntity(PrometheusContentType, builder.result())
          }
        }
      }
    }
  }

  def start(): Future[Http.ServerBinding] = {
    Http().bindAndHandle(routes, "0.0.0.0", metricsPort)
  }
}