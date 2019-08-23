package io.cloudstate.javasupport;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;

// TODO JavaDoc
public interface ServiceCall {
    // TODO JavaDoc
    ServiceCallRef<?> ref();
    // TODO JavaDoc
    Any message();
}
