package io.cloudstate.javasupport;

public interface ServiceCallFactory {
    <T> ServiceCallRef<T> lookup(String serviceName, String methodName);
}
