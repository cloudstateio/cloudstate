package io.cloudstate.javasupport;

import com.google.protobuf.Descriptors;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntity;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntityFactory;
import io.cloudstate.javasupport.impl.AnySupport;
import io.cloudstate.javasupport.impl.eventsourced.AnnotationSupport;
import io.cloudstate.javasupport.impl.eventsourced.EventSourcedStatefulService;
import scala.Option;

import java.util.HashMap;
import java.util.Map;

public final class CloudState {
    private final Map<String, StatefulService> services = new HashMap<>();
    private ClassLoader classLoader;
    private String typeUrlPrefix = AnySupport.DefaultTypeUrlPrefix();
    private AnySupport.Prefer prefer = AnySupport.PREFER_JAVA();

    public CloudState withClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    public CloudState withTypeUrlPrefix(String prefix) {
        this.typeUrlPrefix = prefix;
        return this;
    }

    public CloudState preferJavaProtobufs() {
        this.prefer = AnySupport.PREFER_JAVA();
        return this;
    }

    public CloudState preferScalaProtobufs() {
        this.prefer = AnySupport.PREFER_SCALA();
        return this;
    }

    /**
     * Register an annotated event sourced entity.
     * <p/>
     * The entity class must be annotated with {@link io.cloudstate.javasupport.eventsourced.EventSourcedEntity}.
     *
     * @param entityClass The entity class.
     * @param descriptor The descriptor for the service that this entity implements.
     * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf types when needed.
     * @return This stateful service builder.
     */
    public CloudState registerEventSourcedEntity(Class<?> entityClass, Descriptors.ServiceDescriptor descriptor,
                                                 Descriptors.FileDescriptor... additionalDescriptors) {

        EventSourcedEntity entity = entityClass.getAnnotation(EventSourcedEntity.class);
        if (entity == null) {
             throw new IllegalArgumentException(entityClass + " does not declare an " + EventSourcedEntity.class + " annotation!");
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

        services.put(descriptor.getFullName(), new EventSourcedStatefulService(
                new AnnotationSupport(entityClass, anySupport, descriptor), descriptor,
                anySupport, persistenceId, snapshotEvery
        ));

        return this;
    }

    /**
     * Register an event sourced entity factor.
     * <p/>
     * This is a low level API intended for custom (eg, non reflection based) mechanisms for implementing the entity.
     *
     * @param factory The event sourced factory.
     * @param descriptor The descriptor for the service that this entity implements.
     * @param persistenceId The persistence id for this entity.
     * @param snapshotEvery Specifies how snapshots of the entity state should be made: Zero means use default from configuration file. (Default) Any negative value means never snapshot. Any positive value means snapshot at-or-after that number of events.
     * @param additionalDescriptors Any additional descriptors that should be used to look up protobuf types when needed.
     * @return This stateful service builder.
     */
    public CloudState registerEventSourcedEntity(EventSourcedEntityFactory factory, Descriptors.ServiceDescriptor descriptor,
                                          String persistenceId, int snapshotEvery, Descriptors.FileDescriptor... additionalDescriptors) {
        services.put(descriptor.getFullName(), new EventSourcedStatefulService(factory, descriptor,
                newAnySupport(additionalDescriptors), persistenceId, snapshotEvery));

        return this;

    }

    public void start() {
        new CloudStateRunner(services).run();
    }

    private AnySupport newAnySupport(Descriptors.FileDescriptor[] descriptors) {
        return new AnySupport(descriptors, classLoader, typeUrlPrefix, prefer);
    }

}
