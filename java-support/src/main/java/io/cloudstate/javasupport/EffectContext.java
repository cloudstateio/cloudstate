package io.cloudstate.javasupport;

/**
 * A context that allows instructing the proxy to perform a side effect.
 */
public interface EffectContext extends Context {

    /**
     * Invoke the referenced service call as an effect once this action is completed.
     * <p/>
     * The effect will be performed asynchronously, ie, the proxy won't wait for the effect to finish before sending
     * the reply.
     *
     * @param effect The service call to make as an effect effect.
     */
    default void effect(ServiceCall effect) {
        this.effect(effect, false);
    }

    /**
     * Invoke the referenced service call as an effect once this action is completed.
     *
     * @param effect The service call to make as an effect effect.
     * @param synchronous Whether the effect should be performed synchronously (ie, wait till it has finished before
     *                    sending a reply) or asynchronously.
     */
    void effect(ServiceCall effect, boolean synchronous);
}
