package io.cloudstate.javasupport.crdt;

public interface Vote {
    boolean getSelfVote();
    int getVoters();
    int getVotesFor();
    void vote(boolean vote);

    default boolean isAtLeastOne() {
        return getVotesFor() > 0;
    }
    default boolean isMajority() {
        return getVotesFor() > getVoters() / 2;
    }
    default boolean isUnanimous() {
        return getVotesFor() == getVoters();
    }
}
