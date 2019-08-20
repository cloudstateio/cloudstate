package io.cloudstate.javasupport;

/**
 * A context that allows instructing the proxy to perform a side effect.
 */
public interface EffectContext extends Context {

    /**
     * Perform the given side effect.
     */
    void effect(/* todo parameters */);
}
