package com.trustedrouter.internal;

import java.util.Locale;

/**
 * L1 policy kernel: the pure retry/failover decision table. Internal class
 * with no compatibility guarantees.
 *
 * <p>No I/O, no clock, no OkHttp types beyond pre-extracted header values.
 * Every question the transport engine asks per attempt is answered here, from
 * an immutable {@link AttemptFacts}, as a canonical {@link RetryDecision}.
 * Because the kernel is pure, its whole decision table is unit-testable
 * without a mock transport (see {@code RetryPolicyTest}).
 *
 * <p>Invariants (each line names its enforcing test):
 * <ol>
 *   <li>The failover set {502, 503, 504} is a strict subset of the retry set
 *       {429, 500 and above, verdict-true} —
 *       {@code RetryPolicyTest.everyFailoverableStatusIsAlsoRetryable},
 *       {@code AliasDomainFailoverTest.a503FromThePrimaryReachesAnAlias}.</li>
 *   <li>A 500 NEVER moves domains: a server processed the non-idempotent
 *       inference, and re-sending it elsewhere risks a second generation —
 *       {@code AliasDomainFailoverTest.a500DoesNotMoveToAnotherDomain}.</li>
 *   <li>Aliases exist only for the default host; the control plane always has
 *       exactly one candidate; a custom base URL is never redirected —
 *       {@code AliasDomainFailoverTest.aCustomBaseUrlIsNeverRedirectedToAPublicAlias},
 *       {@code ClientTransportTest.modelCatalogAlwaysUsesControlPlane}.</li>
 *   <li>The {@code x-should-retry} verdict overrides both predicates in both
 *       directions: explicit false forbids retry AND failover, explicit true
 *       forces retry, absent or unparseable keeps the status heuristics —
 *       {@code ShouldRetryHeaderTest.aLabelledSpent502IsNotRetriedAndDoesNotMoveDomains},
 *       {@code ShouldRetryHeaderTest.aLabelledRetryable400IsRetriedEvenThoughTheStatusSaysOtherwise}.</li>
 *   <li>High-level mutations mint one idempotency key before the loop; generic
 *       mutations replay only when the caller supplies one. Any key is re-sent
 *       verbatim across every attempt and domain move —
 *       {@code ClientTransportTest.retriesRateLimitAndPreservesIdempotencyKey}.</li>
 *   <li>Retries happen only before any body bytes are surfaced; a broken open
 *       stream propagates and never reconnects —
 *       {@code StreamingTest.unexpectedEofCannotMasqueradeAsACompletedStream}.</li>
 *   <li>The failover flag governs WHERE, never WHETHER: a pinned client still
 *       retries in place —
 *       {@code ShouldRetryHeaderTest.aPinnedClientStillRetriesInPlace}.</li>
 *   <li>Replay-safe transport errors may move hosts within the flag gating;
 *       HTTP moves additionally require a failoverable status —
 *       {@code AliasDomainFailoverTest.aDeadPrimaryDomainReachesAnAlias}.</li>
 *   <li>Terminal asymmetry is contract: exhausted-status attempts RETURN the
 *       response for the caller to classify, IO exhaustion THROWS —
 *       {@code RetryPolicyTest.statusExhaustionReturnsWhileIoExhaustionThrows},
 *       {@code ClientTransportTest.authenticationAndTransportFailuresHaveSpecificTypes}.</li>
 *   <li>The verdict-false guard inside {@link #failoverable} is deliberately
 *       unreachable from the loop (a false verdict already fails
 *       {@link #retryable}, so {@code failoverable} is never consulted). It is
 *       a documented surviving mutant, mirrored in the sibling SDKs: moved
 *       verbatim, never "fixed", never tested.</li>
 * </ol>
 */
public final class RetryPolicy {
    /** Default number of retries after the first attempt. */
    public static final int DEFAULT_MAX_RETRIES = 2;
    /** First-retry backoff ceiling, doubled per attempt. */
    public static final long BASE_BACKOFF_MILLIS = 500L;
    /** Absolute backoff ceiling. */
    public static final long MAX_BACKOFF_MILLIS = 30_000L;
    /** The exponent stops growing here so the shift can never overflow. */
    public static final int MAX_BACKOFF_EXPONENT = 6;

    private final int maxRetries;

    public RetryPolicy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * Decides what the transport engine does after one attempt. This is the
     * ONLY component that may answer retry/failover questions; the engine
     * merely executes the decision.
     */
    public RetryDecision decide(int attempt, AttemptFacts facts) {
        if (!facts.isReplayable()) {
            return facts.isIoFailure()
                    ? RetryDecision.throwUnavailable()
                    : RetryDecision.returnResponse();
        }
        if (facts.isIoFailure()) {
            if (attempt >= maxRetries) {
                return RetryDecision.throwUnavailable();
            }
            // A connection failure means no server read the request, so
            // another domain cannot double-execute it. The IO branch never
            // consults x-should-retry — no response exists — yet may always
            // move hosts within the flag gating.
            return RetryDecision.retry(facts.isFailoverAllowed(), null);
        }
        if (attempt >= maxRetries
                || !retryable(facts.getStatus(), facts.getShouldRetryVerdict())) {
            return RetryDecision.returnResponse();
        }
        boolean moveHost = facts.isFailoverAllowed()
                && failoverable(facts.getStatus(), facts.getShouldRetryVerdict());
        return RetryDecision.retry(moveHost, facts.getRetryAfterSeconds());
    }

    /**
     * Parses the gateway's explicit verdict, which overrides every heuristic
     * below.
     *
     * <p>A status code cannot say whether a provider already ran. A 502 from
     * "could not reach the provider" and a 502 from "the generation succeeded
     * and then settlement failed" are indistinguishable here, and only the
     * second is dangerous to re-send. The gateway knows and says so, using the
     * same header OpenAI's clients honour.
     *
     * <p>Returns null when the server did not say, leaving behaviour unchanged
     * for older gateways and for deliberately unlabelled paths.
     *
     * @param rawHeaderValue the raw {@code x-should-retry} header value, or
     *     null when the header was absent
     */
    public static Boolean shouldRetryVerdict(String rawHeaderValue) {
        if (rawHeaderValue == null) {
            return null;
        }
        String value = rawHeaderValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Whether we may send this again — independent of WHERE.
     *
     * <p>This used to take {@code regionalFailover} and return it for
     * 502/503/504, so pinning to one host ALSO stopped retrying the gateway
     * statuses entirely: one switch answering two questions. The flag now
     * governs only the destination.
     */
    public static boolean retryable(int status, Boolean verdict) {
        if (verdict != null) {
            return verdict.booleanValue();
        }
        return status == 429 || status >= 500;
    }

    /**
     * Whether this response may move to a DIFFERENT domain. An explicit
     * {@code x-should-retry: false} forbids it outright.
     *
     * <p>Deliberately narrower than {@link #retryable}: a 500 means a server
     * accepted the request and failed inside it, so an inference call may
     * already have run and been billed. Re-sending that to a second domain
     * runs the work again: not a double charge to the caller, but a second
     * upstream generation TrustedRouter pays for, and possibly a different
     * answer. Only statuses that mean "nothing processed this" move hosts.
     */
    public static boolean failoverable(int status, Boolean verdict) {
        if (Boolean.FALSE.equals(verdict)) {
            // Unreachable from the engine loop: a false verdict already failed
            // retryable() above, so decide() never asks. Kept verbatim as the
            // documented surviving mutant shared with the sibling SDKs.
            return false;
        }
        return status == 502 || status == 503 || status == 504;
    }

    /**
     * Immutable per-attempt facts, extracted from the response BEFORE it is
     * closed (or marked as an IO failure when the HTTP client threw).
     */
    public static final class AttemptFacts {
        private final boolean ioFailure;
        private final int status;
        private final Boolean shouldRetryVerdict;
        private final Double retryAfterSeconds;
        private final boolean failoverAllowed;
        private final boolean replayable;

        private AttemptFacts(
                boolean ioFailure,
                int status,
                Boolean shouldRetryVerdict,
                Double retryAfterSeconds,
                boolean failoverAllowed,
                boolean replayable) {
            this.ioFailure = ioFailure;
            this.status = status;
            this.shouldRetryVerdict = shouldRetryVerdict;
            this.retryAfterSeconds = retryAfterSeconds;
            this.failoverAllowed = failoverAllowed;
            this.replayable = replayable;
        }

        /** Facts for an attempt that produced an HTTP response. */
        public static AttemptFacts httpResponse(
                int status,
                String rawShouldRetryHeader,
                Double retryAfterSeconds,
                boolean failoverAllowed) {
            return httpResponse(
                    status, rawShouldRetryHeader, retryAfterSeconds, failoverAllowed, true);
        }

        /** Facts for an HTTP response, including whether replay is safe. */
        public static AttemptFacts httpResponse(
                int status,
                String rawShouldRetryHeader,
                Double retryAfterSeconds,
                boolean failoverAllowed,
                boolean replayable) {
            return new AttemptFacts(
                    false,
                    status,
                    shouldRetryVerdict(rawShouldRetryHeader),
                    retryAfterSeconds,
                    failoverAllowed,
                    replayable);
        }

        /** Facts for an attempt where the HTTP client threw before a response. */
        public static AttemptFacts ioFailure(boolean failoverAllowed) {
            return ioFailure(failoverAllowed, true);
        }

        /** Facts for an I/O failure, including whether replay is safe. */
        public static AttemptFacts ioFailure(boolean failoverAllowed, boolean replayable) {
            return new AttemptFacts(true, 0, null, null, failoverAllowed, replayable);
        }

        public boolean isIoFailure() { return ioFailure; }
        public int getStatus() { return status; }
        public Boolean getShouldRetryVerdict() { return shouldRetryVerdict; }
        public Double getRetryAfterSeconds() { return retryAfterSeconds; }
        public boolean isFailoverAllowed() { return failoverAllowed; }
        public boolean isReplayable() { return replayable; }
    }

    /**
     * Canonical decision: terminal {@code RETURN_RESPONSE} (the caller
     * classifies the status) or {@code THROW} (IO exhaustion), or
     * {@code RETRY} carrying whether to advance the candidate index and the
     * server-requested sleep floor.
     */
    public static final class RetryDecision {
        /** The three decision shapes. */
        public enum Kind { RETURN_RESPONSE, THROW, RETRY }

        private static final RetryDecision RETURN_RESPONSE_DECISION =
                new RetryDecision(Kind.RETURN_RESPONSE, false, null);
        private static final RetryDecision THROW_DECISION =
                new RetryDecision(Kind.THROW, false, null);

        private final Kind kind;
        private final boolean moveHost;
        private final Double retryAfterSeconds;

        private RetryDecision(Kind kind, boolean moveHost, Double retryAfterSeconds) {
            this.kind = kind;
            this.moveHost = moveHost;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        /** Terminal: hand the response back for the caller to classify. */
        public static RetryDecision returnResponse() {
            return RETURN_RESPONSE_DECISION;
        }

        /** Terminal: IO exhaustion; the engine throws unavailable. */
        public static RetryDecision throwUnavailable() {
            return THROW_DECISION;
        }

        /** Retry, optionally advancing the candidate index first. */
        public static RetryDecision retry(boolean moveHost, Double retryAfterSeconds) {
            return new RetryDecision(Kind.RETRY, moveHost, retryAfterSeconds);
        }

        public Kind getKind() { return kind; }
        public boolean isMoveHost() { return moveHost; }
        /** Server-requested sleep floor in seconds, or null when it did not say. */
        public Double getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
