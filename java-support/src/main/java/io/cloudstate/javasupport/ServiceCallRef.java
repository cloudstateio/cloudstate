package io.cloudstate.javasupport;

import com.google.protobuf.Descriptors;

// TODO JavaDoc
public interface ServiceCallRef<T> {
    // TODO JavaDoc
    Descriptors.MethodDescriptor method();
    // TODO JavaDoc
    ServiceCall createCall(T message);
}
