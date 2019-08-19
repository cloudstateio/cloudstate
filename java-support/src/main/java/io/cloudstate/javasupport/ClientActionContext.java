package io.cloudstate.javasupport;

public interface ClientActionContext extends Context {
    void fail(String errorMessage);
    void forward(/* todo parameters */);
}
