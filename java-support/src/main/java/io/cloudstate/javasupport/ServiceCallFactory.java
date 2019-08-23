package io.cloudstate.javasupport;

// TODO JavaDoc
public interface ServiceCallFactory {
    // TODO JavaDoc
    <T> ServiceCallRef<T> lookup(String serviceName, String methodName);
}
