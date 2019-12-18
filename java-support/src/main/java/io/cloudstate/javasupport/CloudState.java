package io.cloudstate.javasupport;

import com.typesafe.config.Config;
import com.google.protobuf.Descriptors;
import io.cloudstate.javasupport.crdt.CrdtEntity;
import io.cloudstate.javasupport.crdt.CrdtEntityFactory;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntity;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntityFactory;
import io.cloudstate.javasupport.impl.AnySupport;
import io.cloudstate.javasupport.impl.crdt.AnnotationBasedCrdtSupport;
import io.cloudstate.javasupport.impl.crdt.CrdtStatefulService;
import io.cloudstate.javasupport.impl.eventsourced.AnnotationBasedEventSourcedSupport;
import io.cloudstate.javasupport.impl.eventsourced.EventSourcedStatefulService;

import akka.Done;
import java.util.concurrent.CompletionStage;
import java.util.HashMap;
import java.util.Map;

/**
 * The CloudState class is the main interface to configuring entities to deploy, and subsequently
 * starting a local server which will expose these entities to the CloudState Proxy Sidecar.
 */
public final class CloudState {
  private final Map<String, StatefulService> services = new HashMap<>();
  private ClassLoader classLoader = getClass().getClassLoader();
  private String typeUrlPrefix = AnySupport.DefaultTypeUrlPrefix();
  private AnySupport.Prefer prefer = AnySupport.PREFER_JAVA();

  /**
   * Sets the ClassLoader to be used for reflective access, the default value is the ClassLoader of
   * the CloudState class.
   *
   * @param classLoader A non-null ClassLoader to be used for reflective access.
   * @return This CloudState instance.
   */
  public CloudState withClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
    return this;
  }

  /**
   * Sets the type URL prefix to be used when serializing and deserializing types from and to
   * Protobyf Any values. Defaults to "type.googleapis.com".
   *
   * @param prefix the type URL prefix to be used.
   * @return This CloudState instance.
   */
  public CloudState withTypeUrlPrefix(String prefix) {
    this.typeUrlPrefix = prefix;
    return this;
  }

  /**
   * When locating protobufs, if both a Java and a ScalaPB generated class is found on the
   * classpath, this specifies that Java should be preferred.
   *
   * @return This CloudState instance.
   */
  public CloudState preferJavaProtobufs() {
    this.prefer = AnySupport.PREFER_JAVA();
    return this;
  }

  /**
   * When locating protobufs, if both a Java and a ScalaPB generated class is found on the
   * classpath, this specifies that Scala should be preferred.
   *
   * @return This CloudState instance.
   */
  public CloudState preferScalaProtobufs() {
    this.prefer = AnySupport.PREFER_SCALA();
    return this;
  }

  /**
   * Register an annotated event sourced entity.
   *
   * <p>The entity class must be annotated with {@link
   * io.cloudstate.javasupport.eventsourced.EventSourcedEntity}.
   *
   * @param entityClass The entity class.
   * @param descriptor The descriptor for the service that this entity implements.
   * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf
   *     types when needed.
   * @return This stateful service builder.
   */
  public CloudState registerEventSourcedEntity(
      Class<?> entityClass,
      Descriptors.ServiceDescriptor descriptor,
      Descriptors.FileDescriptor... additionalDescriptors) {

    EventSourcedEntity entity = entityClass.getAnnotation(EventSourcedEntity.class);
    if (entity == null) {
      throw new IllegalArgumentException(
          entityClass + " does not declare an " + EventSourcedEntity.class + " annotation!");
    }

    final String persistenceId;
    final int snapshotEvery;
    if (entity.persistenceId().isEmpty()) {
      persistenceId = entityClass.getSimpleName();
      snapshotEvery = 0; // Default
    } else {
      persistenceId = entity.persistenceId();
      snapshotEvery = entity.snapshotEvery();
    }

    final AnySupport anySupport = newAnySupport(additionalDescriptors);

    services.put(
        descriptor.getFullName(),
        new EventSourcedStatefulService(
            new AnnotationBasedEventSourcedSupport(entityClass, anySupport, descriptor),
            descriptor,
            anySupport,
            persistenceId,
            snapshotEvery));

    return this;
  }

  /**
   * Register an event sourced entity factor.
   *
   * <p>This is a low level API intended for custom (eg, non reflection based) mechanisms for
   * implementing the entity.
   *
   * @param factory The event sourced factory.
   * @param descriptor The descriptor for the service that this entity implements.
   * @param persistenceId The persistence id for this entity.
   * @param snapshotEvery Specifies how snapshots of the entity state should be made: Zero means use
   *     default from configuration file. (Default) Any negative value means never snapshot. Any
   *     positive value means snapshot at-or-after that number of events.
   * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf
   *     types when needed.
   * @return This stateful service builder.
   */
  public CloudState registerEventSourcedEntity(
      EventSourcedEntityFactory factory,
      Descriptors.ServiceDescriptor descriptor,
      String persistenceId,
      int snapshotEvery,
      Descriptors.FileDescriptor... additionalDescriptors) {
    services.put(
        descriptor.getFullName(),
        new EventSourcedStatefulService(
            factory,
            descriptor,
            newAnySupport(additionalDescriptors),
            persistenceId,
            snapshotEvery));

    return this;
  }

  /**
   * Register an annotated CRDT entity.
   *
   * <p>The entity class must be annotated with {@link io.cloudstate.javasupport.crdt.CrdtEntity}.
   *
   * @param entityClass The entity class.
   * @param descriptor The descriptor for the service that this entity implements.
   * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf
   *     types when needed.
   * @return This stateful service builder.
   */
  public CloudState registerCrdtEntity(
      Class<?> entityClass,
      Descriptors.ServiceDescriptor descriptor,
      Descriptors.FileDescriptor... additionalDescriptors) {

    CrdtEntity entity = entityClass.getAnnotation(CrdtEntity.class);
    if (entity == null) {
      throw new IllegalArgumentException(
          entityClass + " does not declare an " + CrdtEntity.class + " annotation!");
    }

    final AnySupport anySupport = newAnySupport(additionalDescriptors);

    services.put(
        descriptor.getFullName(),
        new CrdtStatefulService(
            new AnnotationBasedCrdtSupport(entityClass, anySupport, descriptor),
            descriptor,
            anySupport));

    return this;
  }

  /**
   * Register an CRDt entity factory.
   *
   * <p>This is a low level API intended for custom (eg, non reflection based) mechanisms for
   * implementing the entity.
   *
   * @param factory The CRDT factory.
   * @param descriptor The descriptor for the service that this entity implements.
   * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf
   *     types when needed.
   * @return This stateful service builder.
   */
  public CloudState registerCrdtEntity(
      CrdtEntityFactory factory,
      Descriptors.ServiceDescriptor descriptor,
      Descriptors.FileDescriptor... additionalDescriptors) {
    services.put(
        descriptor.getFullName(),
        new CrdtStatefulService(factory, descriptor, newAnySupport(additionalDescriptors)));

    return this;
  }

  /**
   * Starts a server with the configured entities.
   *
   * @return a CompletionStage which will be completed when the server has shut down.
   */
  public CompletionStage<Done> start() {
    return createRunner().run();
  }

  /**
   * Starts a server with the configured entities, using the supplied configuration.
   *
   * @return a CompletionStage which will be completed when the server has shut down.
   */
  public CompletionStage<Done> start(Config config) {
    return createRunner(config).run();
  }

  /**
   * Creates a CloudStateRunner using the currently configured services. In order to start the
   * server, `run()` must be invoked on the returned CloudStateRunner.
   *
   * @return a CloudStateRunner
   */
  public CloudStateRunner createRunner() {
    return new CloudStateRunner(services);
  }

  /**
   * Creates a CloudStateRunner using the currently configured services, using the supplied
   * configuration. In order to start the server, `run()` must be invoked on the returned
   * CloudStateRunner.
   *
   * @return a CloudStateRunner
   */
  public CloudStateRunner createRunner(Config config) {
    return new CloudStateRunner(services, config);
  }

  private AnySupport newAnySupport(Descriptors.FileDescriptor[] descriptors) {
    return new AnySupport(descriptors, classLoader, typeUrlPrefix, prefer);
  }
}
