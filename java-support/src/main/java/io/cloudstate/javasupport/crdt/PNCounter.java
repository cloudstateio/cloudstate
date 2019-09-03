package io.cloudstate.javasupport.crdt;

/**
 * A Positive-Negative Counter.
 * <p>
 * A Positive-Negative Counter is a counter that allows both incrementing, and decrementing. It is based on two
 * {@link GCounter}'s, a positive one that is incremented for every increment, and a negative one that is incremented
 * for every decrement. The current value of the counter is calculated by subtracting the negative counter from the
 * positive counter.
 */
public interface PNCounter extends Crdt {
    /**
     * Get the current value of the counter.
     *
     * @return The current value of the counter.
     */
    long getValue();

    /**
     * Increment the counter.
     * <p>
     * If <code>by</code> is negative, then the counter will be decremented by that much instead.
     *
     * @param by The amount to increment the counter by.
     * @return The new value of the counter.
     */
    long increment(long by);

    /**
     * Decrement the counter.
     * <p>
     * If <code>by</code> is negative, then the counter will be incremented by that much instead.
     *
     * @param by The amount to decrement the counter by.
     * @return The new value of the counter.
     */
    long decrement(long by);
}
