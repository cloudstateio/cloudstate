/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const should = require("chai").should();
const Vote = require("../../src/crdts/vote");
const protobufHelper = require("../../src/protobuf-helper");

const CrdtDelta = protobufHelper.moduleRoot.cloudstate.crdt.CrdtDelta;
const CrdtState = protobufHelper.moduleRoot.cloudstate.crdt.CrdtState;

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

function roundTripState(state) {
  return CrdtState.decode(CrdtState.encode(state).finish());
}

function voteState(totalVoters, votesFor, selfVote) {
  return roundTripState({
    vote: {
      totalVoters: totalVoters,
      votesFor: votesFor,
      selfVote: selfVote
    }
  });
}

describe("Vote", () => {

  it("should have zero voters, votes and no self vote when instantiated", () => {
    const vote = new Vote();
    vote.votesFor.should.equal(0);
    vote.totalVoters.should.equal(1);
    vote.vote.should.equal(false);
  });

  it("should reflect a state update", () => {
    const vote = new Vote();
    vote.applyState(voteState(5, 3, true));
    vote.votesFor.should.equal(3);
    vote.totalVoters.should.equal(5);
    vote.vote.should.equal(true);
  });

  it("should reflect a delta update", () => {
    const vote = new Vote();
    vote.applyState(voteState(5, 3, false))
    vote.applyDelta(roundTripDelta({
      vote: {
        totalVotersDelta: -1,
        votesForDelta: 1,
      }
    }));
    vote.votesFor.should.equal(4);
    vote.totalVoters.should.equal(4);
    vote.vote.should.equal(false);
  });

  it("should generate deltas", () => {
    const vote = new Vote();
    vote.vote = true;
    roundTripDelta(vote.getAndResetDelta()).vote.selfVote.should.be.true;
    should.equal(vote.getAndResetDelta(), null);
    vote.votesFor.should.equal(1);
    vote.vote.should.equal(true);

    vote.vote = false;
    roundTripDelta(vote.getAndResetDelta()).vote.selfVote.should.be.false;
    should.equal(vote.getAndResetDelta(), null);
    vote.votesFor.should.equal(0);
    vote.vote.should.equal(false);
  });

  it("should return its state", () => {
    const vote = new Vote();
    const state1 = roundTripState(vote.getStateAndResetDelta());
    state1.vote.selfVote.should.be.false;
    state1.vote.votesFor.should.equal(0);
    state1.vote.totalVoters.should.equal(1);

    vote.vote = true;
    const state2 = roundTripState(vote.getStateAndResetDelta());
    state2.vote.selfVote.should.be.true;
    state2.vote.votesFor.should.equal(1);
    state2.vote.totalVoters.should.equal(1);

    should.equal(vote.getAndResetDelta(), null);
  });

  it("should correctly calculate a majority vote", () => {
    const vote = new Vote();
    vote.applyState(voteState(5, 3, true));
    vote.majority.should.equal(true);
    vote.applyState(voteState(5, 2, true));
    vote.majority.should.equal(false);
    vote.applyState(voteState(6, 3, true));
    vote.majority.should.equal(false);
    vote.applyState(voteState(6, 4, true));
    vote.majority.should.equal(true);
    vote.applyState(voteState(1, 0, false));
    vote.majority.should.equal(false);
    vote.applyState(voteState(1, 1, true));
    vote.majority.should.equal(true);
  });

  it("should correctly calculate an at least one vote", () => {
    const vote = new Vote();
    vote.applyState(voteState(1, 0, false));
    vote.atLeastOne.should.equal(false);
    vote.applyState(voteState(5, 0, false));
    vote.atLeastOne.should.equal(false);
    vote.applyState(voteState(1, 1, true));
    vote.atLeastOne.should.equal(true);
    vote.applyState(voteState(5, 1, true));
    vote.atLeastOne.should.equal(true);
    vote.applyState(voteState(5, 3, true));
    vote.atLeastOne.should.equal(true);
  });

  it("should correctly calculate an all votes", () => {
    const vote = new Vote();
    vote.applyState(voteState(1, 0, false));
    vote.all.should.equal(false);
    vote.applyState(voteState(5, 0, false));
    vote.all.should.equal(false);
    vote.applyState(voteState(1, 1, true));
    vote.all.should.equal(true);
    vote.applyState(voteState(5, 3, true));
    vote.all.should.equal(false);
    vote.applyState(voteState(5, 5, true));
    vote.all.should.equal(true);
  });

});