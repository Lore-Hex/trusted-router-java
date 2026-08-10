package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.internal.JitterSleeper;
import com.trustedrouter.internal.RetryPolicy;
import com.trustedrouter.internal.RetryPolicy.AttemptFacts;
import com.trustedrouter.internal.RetryPolicy.RetryDecision;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests of the pure L1 kernel: the whole decision table without
 * a mock transport. These double as the mutation net for the policy
 * constants and predicates.
 */
final class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy(2);

    // --- terminals (invariant 9) ---

    @Test void statusExhaustionReturnsWhileIoExhaustionThrows() {
        RetryDecision status = policy.decide(2, http(503, null, null, true));
        RetryDecision io = policy.decide(2, AttemptFacts.ioFailure(true));

        assertThat(status.getKind()).as("caller classifies the status")
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
        assertThat(io.getKind()).as("no response exists to return")
                .isEqualTo(RetryDecision.Kind.THROW);
    }

    @Test void nonRetryableStatusesReturnImmediatelyEvenWithAttemptsLeft() {
        assertThat(policy.decide(0, http(200, null, null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
        assertThat(policy.decide(0, http(400, null, null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
        assertThat(policy.decide(0, http(404, null, null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
    }

    // --- retry set and failover subset (invariants 1 and 2) ---

    @Test void everyFailoverableStatusIsAlsoRetryable() {
        for (int status : new int[] {502, 503, 504}) {
            assertThat(RetryPolicy.retryable(status, null))
                    .as("failoverable %s must be retryable", status).isTrue();
            assertThat(RetryPolicy.failoverable(status, null)).isTrue();
        }
        // The subset is strict: 429 and 500 retry but never move.
        assertThat(RetryPolicy.retryable(429, null)).isTrue();
        assertThat(RetryPolicy.failoverable(429, null)).isFalse();
        assertThat(RetryPolicy.retryable(500, null)).isTrue();
        assertThat(RetryPolicy.failoverable(500, null)).isFalse();
    }

    @Test void a500RetriesInPlaceAndNeverMovesHost() {
        RetryDecision decision = policy.decide(0, http(500, null, null, true));
        assertThat(decision.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(decision.isMoveHost())
                .as("a server already processed the inference").isFalse();
    }

    @Test void gatewayStatusesMoveHostWhenFailoverIsAllowed() {
        for (int status : new int[] {502, 503, 504}) {
            RetryDecision decision = policy.decide(0, http(status, null, null, true));
            assertThat(decision.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
            assertThat(decision.isMoveHost()).as("status %s", status).isTrue();
        }
    }

    // --- the flag governs WHERE, never WHETHER (invariant 7) ---

    @Test void aPinnedCallStillRetriesButNeverMoves() {
        RetryDecision decision = policy.decide(0, http(503, null, null, false));
        assertThat(decision.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(decision.isMoveHost()).isFalse();
    }

    // --- verdict overrides (invariant 4) ---

    @Test void verdictFalseForbidsRetryRegardlessOfStatus() {
        assertThat(policy.decide(0, http(502, "false", null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
        assertThat(policy.decide(0, http(503, "false", null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
    }

    @Test void verdictTrueForcesRetryOnAStatusThatSaysOtherwise() {
        RetryDecision decision = policy.decide(0, http(400, "true", null, true));
        assertThat(decision.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(decision.isMoveHost())
                .as("verdict-true forces retry, not failover; 400 is not failoverable")
                .isFalse();
    }

    @Test void absentOrUnparseableVerdictsKeepTheStatusHeuristics() {
        assertThat(RetryPolicy.shouldRetryVerdict(null)).isNull();
        assertThat(RetryPolicy.shouldRetryVerdict("maybe")).isNull();
        assertThat(RetryPolicy.shouldRetryVerdict(" TRUE ")).isEqualTo(Boolean.TRUE);
        assertThat(RetryPolicy.shouldRetryVerdict("False")).isEqualTo(Boolean.FALSE);
        assertThat(policy.decide(0, http(503, "garbled", null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETRY);
    }

    // --- IO branch (invariant 8) ---

    @Test void ioFailuresRetryAndMayAlwaysMoveWithinTheFlagGating() {
        RetryDecision allowed = policy.decide(0, AttemptFacts.ioFailure(true));
        RetryDecision pinned = policy.decide(0, AttemptFacts.ioFailure(false));

        assertThat(allowed.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(allowed.isMoveHost()).as("no server saw the request").isTrue();
        assertThat(pinned.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(pinned.isMoveHost()).isFalse();
    }

    // --- retry-after floor plumbing and backoff bounds (invariant 5 support) ---

    @Test void retryDecisionCarriesTheServerRequestedFloor() {
        RetryDecision decision = policy.decide(
                0, http(429, null, Double.valueOf(0.01d), true));
        assertThat(decision.getKind()).isEqualTo(RetryDecision.Kind.RETRY);
        assertThat(decision.getRetryAfterSeconds()).isEqualTo(0.01d);
    }

    @Test void jitteredDelayHonorsTheFloorAsAMaxNeverAReplacement() {
        JitterSleeper sleeper = new JitterSleeper();
        for (int i = 0; i < 50; i++) {
            long floored = sleeper.delayMillis(0, Double.valueOf(2.0d));
            assertThat(floored).isGreaterThanOrEqualTo(2_000L);
            long unfloored = sleeper.delayMillis(0, null);
            assertThat(unfloored).isBetween(0L, RetryPolicy.BASE_BACKOFF_MILLIS);
        }
    }

    @Test void backoffCeilingIsExponentCappedAndNeverExceedsTheMax() {
        JitterSleeper sleeper = new JitterSleeper();
        for (int attempt : new int[] {0, 3, RetryPolicy.MAX_BACKOFF_EXPONENT, 40, Integer.MAX_VALUE}) {
            for (int i = 0; i < 20; i++) {
                assertThat(sleeper.delayMillis(attempt, null))
                        .isBetween(0L, RetryPolicy.MAX_BACKOFF_MILLIS);
            }
        }
    }

    @Test void zeroMaxRetriesIsTerminalOnTheFirstAttempt() {
        RetryPolicy none = new RetryPolicy(0);
        assertThat(none.decide(0, http(503, null, null, true)).getKind())
                .isEqualTo(RetryDecision.Kind.RETURN_RESPONSE);
        assertThat(none.decide(0, AttemptFacts.ioFailure(true)).getKind())
                .isEqualTo(RetryDecision.Kind.THROW);
    }

    private static AttemptFacts http(
            int status, String verdictHeader, Double retryAfterSeconds, boolean failoverAllowed) {
        return AttemptFacts.httpResponse(status, verdictHeader, retryAfterSeconds, failoverAllowed);
    }
}
