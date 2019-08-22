package io.cloudstate.javasupport;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;

public interface ServiceCall {
    ServiceCallRef<?> ref();
    Any message();
}
