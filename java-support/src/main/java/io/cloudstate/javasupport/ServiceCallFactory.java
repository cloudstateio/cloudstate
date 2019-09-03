package io.cloudstate.javasupport;

/**
 * A service call factory.
 * <p>
 * This is used to create {@link ServiceCall}'s that can be passed to {@link EffectContext#effect(ServiceCall)} and
 * {@link ClientActionContext#forward(ServiceCall)} f}.
 */
public interface ServiceCallFactory {

    /**
     * Lookup a reference to the service call with the given name and method.
     *
     * @param serviceName The fully qualified name of a gRPC service that this stateful service serves.
     * @param methodName The name of a method on the gRPC service.
     * @param messageType The expected type of the input message to the method.
     * @param <T> The type of the parameter that it accepts.
     * @return A reference to the service call.
     * @throws java.util.NoSuchElementException if the service or method is not found.
     * @throws IllegalArgumentException if the accepted input type for the method doesn't match
     * <code>messageType</code>.
     */
    <T> ServiceCallRef<T> lookup(String serviceName, String methodName, Class<T> messageType);
}
