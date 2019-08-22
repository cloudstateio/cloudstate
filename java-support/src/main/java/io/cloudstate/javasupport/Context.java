package io.cloudstate.javasupport;

/**
 * Root class of all contexts.
 */
public interface Context {
    /**
     * Get the service call factory for this stateful service.
     */
    ServiceCallFactory serviceCallFactory();
}
