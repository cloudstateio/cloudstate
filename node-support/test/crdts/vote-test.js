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

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

function voteDelta(totalVoters, votesFor, selfVote) {
  return roundTripDelta({
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
    should.equal(vote.getAndResetDelta(), null);
  });

  it("should reflect an initial delta", () => {
    const vote = new Vote();
    vote.applyDelta(voteDelta(5, 3, true));
    vote.votesFor.should.equal(3);
    vote.totalVoters.should.equal(5);
    vote.vote.should.equal(true);
    should.equal(vote.getAndResetDelta(), null);
  });

  it("should reflect a delta update", () => {
    const vote = new Vote();
    vote.applyDelta(voteDelta(5, 3, false))
    vote.applyDelta(roundTripDelta({
      vote: {
        totalVoters: 4,
        votesFor: 2,
      }
    }));
    vote.votesFor.should.equal(2);
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

  it("should correctly calculate a majority vote", () => {
    const vote = new Vote();
    vote.applyDelta(voteDelta(5, 3, true));
    vote.majority.should.equal(true);
    vote.applyDelta(voteDelta(5, 2, true));
    vote.majority.should.equal(false);
    vote.applyDelta(voteDelta(6, 3, true));
    vote.majority.should.equal(false);
    vote.applyDelta(voteDelta(6, 4, true));
    vote.majority.should.equal(true);
    vote.applyDelta(voteDelta(1, 0, false));
    vote.majority.should.equal(false);
    vote.applyDelta(voteDelta(1, 1, true));
    vote.majority.should.equal(true);
  });

  it("should correctly calculate an at least one vote", () => {
    const vote = new Vote();
    vote.applyDelta(voteDelta(1, 0, false));
    vote.atLeastOne.should.equal(false);
    vote.applyDelta(voteDelta(5, 0, false));
    vote.atLeastOne.should.equal(false);
    vote.applyDelta(voteDelta(1, 1, true));
    vote.atLeastOne.should.equal(true);
    vote.applyDelta(voteDelta(5, 1, true));
    vote.atLeastOne.should.equal(true);
    vote.applyDelta(voteDelta(5, 3, true));
    vote.atLeastOne.should.equal(true);
  });

  it("should correctly calculate an all votes", () => {
    const vote = new Vote();
    vote.applyDelta(voteDelta(1, 0, false));
    vote.all.should.equal(false);
    vote.applyDelta(voteDelta(5, 0, false));
    vote.all.should.equal(false);
    vote.applyDelta(voteDelta(1, 1, true));
    vote.all.should.equal(true);
    vote.applyDelta(voteDelta(5, 3, true));
    vote.all.should.equal(false);
    vote.applyDelta(voteDelta(5, 5, true));
    vote.all.should.equal(true);
  });

  it("should support empty initial deltas (for ORMap added)", () => {
    const vote = new Vote();
    vote.votesFor.should.equal(0);
    vote.totalVoters.should.equal(1);
    vote.vote.should.equal(false);
    should.equal(vote.getAndResetDelta(), null);
    const delta = roundTripDelta(vote.getAndResetDelta(/* initial = */ true))
    delta.vote.selfVote.should.be.false;
  });

});
