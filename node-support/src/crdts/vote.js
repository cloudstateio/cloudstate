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

const util = require("util");

function Vote() {
  let currentSelfVote = false;
  let currentVotesFor = 0;
  let currentTotalVoters = 1;
  let delta = null;

  Object.defineProperty(this, "votesFor", {
    get: function () {
      return currentVotesFor;
    }
  });

  Object.defineProperty(this, "totalVoters", {
    get: function () {
      return currentTotalVoters;
    }
  });

  Object.defineProperty(this, "atLeastOne", {
    get: function () {
      return currentVotesFor > 0;
    }
  });

  Object.defineProperty(this, "majority", {
    get: function () {
      return currentVotesFor > currentTotalVoters / 2;
    }
  });

  Object.defineProperty(this, "all", {
    get: function () {
      return currentVotesFor === currentTotalVoters;
    }
  });

  Object.defineProperty(this, "vote", {
    get: function () {
      return currentSelfVote;
    },
    set: function(value) {
      if (value && !currentSelfVote) {
        currentSelfVote = true;
        currentVotesFor += 1;
        delta = true;
      } else if (!value && currentSelfVote) {
        currentSelfVote = false;
        currentVotesFor -= 1;
        delta = false;
      }
    }
  });

  this.getAndResetDelta = function () {
    if (delta !== null) {
      const vote = delta;
      delta = null;
      return {
        vote: {
          selfVote: vote
        }
      };
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta) {
    if (!delta.vote) {
      throw new Error(util.format("Cannot apply delta %o to Vote", delta));
    }
    currentVotesFor = delta.vote.votesFor;
    currentTotalVoters = delta.vote.totalVoters;
  };

  this.getStateAndResetDelta = function () {
    delta = null;
    return {
      vote: {
        selfVote: currentSelfVote,
        totalVoters: currentTotalVoters,
        votesFor: currentVotesFor
      }
    };
  };

  this.applyState = function (state) {
    if (!state.vote) {
      throw new Error(util.format("Cannot apply state %o to Vote", state));
    }
    currentSelfVote = state.vote.selfVote;
    currentVotesFor = state.vote.votesFor;
    currentTotalVoters = state.vote.totalVoters;
  };

  this.toString = function () {
    return "Vote(" + currentSelfVote + ")";
  };
}

module.exports = Vote;
