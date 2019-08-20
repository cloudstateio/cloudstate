package io.cloudstate.javasupport;

/**
 * Context that provides client actions, which include failing and forwarding.
 * <p/>
 * These contexts are typically made available in response to commands.
 */
public interface ClientActionContext extends Context {
    /**
     * Fail the command with the given message.
     *
     * @param errorMessage The error message to send to the client.
     */
    void fail(String errorMessage);

    /**
     * Instruct the proxy to forward handling of this command to another entity served by this stateful function.
     * <p/>
     * The command will be forwarded after successful completion of handling this command, including any persistence
     * that this command does.
     */
    void forward(/* todo parameters */);
}
