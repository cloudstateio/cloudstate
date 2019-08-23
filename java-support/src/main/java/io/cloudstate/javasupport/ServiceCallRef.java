package io.cloudstate.javasupport;

import com.google.protobuf.Descriptors;

public interface ServiceCallRef<T> {
    Descriptors.MethodDescriptor method();
    ServiceCall createCall(T message);
}
