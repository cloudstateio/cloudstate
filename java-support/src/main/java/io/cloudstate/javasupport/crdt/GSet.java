package io.cloudstate.javasupport.crdt;

import java.util.Set;

/**
 * A Grow-only Set.
 * <p>
 * A Grow-only Set can have elements added to it, but cannot have elements removed from it.
 * <p>
 * Care needs to be taken to ensure that the serialized value of elements in the set is stable. For example, if using
 * protobufs, the serialized value of any maps contain in the protobuf is not stable, and can yield a different set of
 * bytes for the same logically equal element. Hence maps, should be avoided. Additionally, some changes in protobuf
 * schemas which are backwards compatible from a protobuf perspective, such as changing from sint32 to int32, do result
 * in different serialized bytes, and so must be avoided.
 *
 * @param <T> The value of the set elements
 */
public interface GSet<T> extends Crdt, Set<T> {

    /**
     * Remove is not support on a Grow-only set.
     */
    @Override
    default boolean remove(Object o) {
        throw new UnsupportedOperationException("Remove is not supported on a Grow-only Set.");
    }
}
