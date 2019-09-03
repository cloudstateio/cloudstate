package io.cloudstate.javasupport.crdt;

/**
 * Factory for creating CRDTs.
 * <p>
 * This is used both by CRDT contexts that allow creating CRDTs, as well as by CRDTs that allow nesting other CRDTs.
 * <p>
 * CRDTs may only be created by a supplied CRDT factory, CRDTs created any other way will not be known by the
 * library and so won't have their deltas synced to and from the proxy.
 */
public interface CrdtFactory {
    /**
     * Create a new GCounter.
     *
     * @return The new GCounter.
     */
    GCounter newGCounter();

    /**
     * Create a new PNCounter.
     *
     * @return The new PNCounter.
     */
    PNCounter newPNCounter();

    /**
     * Create a new GSet.
     *
     * @return The new GSet.
     */
    <T> GSet<T> newGSet();

    /**
     * Create a new ORSet.
     *
     * @return The new ORSet.
     */
    <T> ORSet<T> newORSet();

    /**
     * Create a new Flag.
     *
     * @return The new Flag.
     */
    Flag newFlag();

    /**
     * Create a new LWWRegister.
     *
     * @return The new LWWRegister.
     */
    <T> LWWRegister<T> newLWWRegister(T value);

    /**
     * Create a new ORMap.
     *
     * @return The new ORMap.
     */
    <K, V extends Crdt> ORMap<K, V> newORMap();

    /**
     * Create a new Vote.
     *
     * @return The new Vote.
     */
    Vote newVote();
}
