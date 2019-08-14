package io.cloudstate.java;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.http.javadsl.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.japi.Function;
import static java.util.Objects.requireNonNull;

import akka.grpc.javadsl.ServiceHandler;

import java.util.concurrent.CompletionStage;
import scala.compat.java8.FutureConverters;

public final class Cloudstate {
  final ActorSystem system;
  final Materializer materializer;
  final StatefulService service;

  public Cloudstate(final StatefulService service) {
    this.service = requireNonNull(service, "StatefulService must not be null!");
    final Config conf = ConfigFactory.load();
    this.system = ActorSystem.create("StatefulService", conf.getConfig("cloudstate.system").withFallback(conf));
    this.materializer = ActorMaterializer.create(this.system);
  }

  public static Function<HttpRequest, CompletionStage<HttpResponse>> compileRoute() {
    return null;
  }

  public CompletionStage<Done> run() {
    Config config = system.settings().config();
    return Http.get(system).bindAndHandleAsync(
      ServiceHandler.concatOrNotFound(null, null),
      ConnectHttp.toHost(config.getString("cloudstate.host"), config.getInt("cloudstate.port")),
      materializer).thenCompose(i -> FutureConverters.toJava(system.terminate())).thenApply(i -> Done.done());
  }
}