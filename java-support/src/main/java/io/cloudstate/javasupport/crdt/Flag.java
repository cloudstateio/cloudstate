package io.cloudstate.javasupport.crdt;

/**
 * A flag CRDT.
 * <p/>
 * A flag is a boolean value that starts out as <code>false</code>, and once set to <code>true</code>, stays
 * <code>true</code>, it cannot be set back to <code>false</code>.
 */
public interface Flag extends Crdt {
    /**
     * Whether this flag is enabled.
     *
     * @return True if the flag is enabled.
     */
    boolean isEnabled();

    /**
     * Enable this flag.
     */
    void enable();
}
