package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import okhttp3.Response;

/**
 * L3 transport engine: THE single retry/failover loop. Internal class with
 * no compatibility guarantees.
 *
 * <p>This is the ONLY place in the SDK where a base-URL candidate index
 * advances and the only component that sleeps (through its {@link Sleeper}).
 * Every request mode — buffered JSON, raw, all streaming entries, absolute
 * metadata — funnels through {@link #executeUrls}; the async client is a
 * pure facade over this class. The engine never drains a success body (that
 * is what lets streaming share it) and never retries after the first
 * surfaced body byte.
 *
 * <p>Plane routing: the INFERENCE plane gets the multi-candidate list from
 * {@link CandidateUrls}; the CONTROL plane and absolute fetches get a
 * singleton list, so failover is structurally impossible there — list
 * LENGTH is the gate, not a second flag.
 *
 * <p>Invariants (each line names its enforcing test):
 * <ol>
 *   <li>The failover set {502, 503, 504} is a strict subset of the retry set
 *       {429, 500 and above, verdict-true} —
 *       {@code RetryPolicyTest.everyFailoverableStatusIsAlsoRetryable},
 *       {@code AliasDomainFailoverTest.a503FromThePrimaryReachesAnAlias}.</li>
 *   <li>A 500 NEVER moves domains — a server processed the non-idempotent
 *       inference; re-sending elsewhere risks a second generation —
 *       {@code AliasDomainFailoverTest.a500DoesNotMoveToAnotherDomain}.</li>
 *   <li>Aliases exist only for the default host; the control plane always
 *       has exactly one candidate; custom bases are never redirected —
 *       {@code AliasDomainFailoverTest.aCustomBaseUrlIsNeverRedirectedToAPublicAlias},
 *       {@code ClientTransportTest.modelCatalogAlwaysUsesControlPlane}.</li>
 *   <li>{@code x-should-retry} overrides both predicates in both directions —
 *       {@code ShouldRetryHeaderTest.aLabelledSpent502IsNotRetriedAndDoesNotMoveDomains},
 *       {@code ShouldRetryHeaderTest.aLabelledRetryable400IsRetriedEvenThoughTheStatusSaysOtherwise}.</li>
 *   <li>The idempotency key is minted once per logical call before the loop
 *       and re-sent verbatim across every attempt and domain move —
 *       {@code ClientTransportTest.retriesRateLimitAndPreservesIdempotencyKey}.</li>
 *   <li>Retries happen only before any body bytes are surfaced; a broken
 *       open stream propagates, never reconnects ({@code EventStream} has no
 *       reconnect path) —
 *       {@code StreamingTest.unexpectedEofCannotMasqueradeAsACompletedStream}.</li>
 *   <li>The failover flag governs WHERE, never WHETHER — a pinned client
 *       still retries in place —
 *       {@code ShouldRetryHeaderTest.aPinnedClientStillRetriesInPlace}.</li>
 *   <li>Transport errors (no server saw the request) may always move hosts
 *       within the flag gating; HTTP moves additionally require a
 *       failoverable status —
 *       {@code AliasDomainFailoverTest.aDeadPrimaryDomainReachesAnAlias}.</li>
 *   <li>Terminal asymmetry is contract: exhausted-status attempts RETURN the
 *       response for the caller to classify, IO exhaustion THROWS
 *       {@code InternalException(503)} —
 *       {@code RetryPolicyTest.statusExhaustionReturnsWhileIoExhaustionThrows},
 *       {@code ClientTransportTest.authenticationAndTransportFailuresHaveSpecificTypes}.</li>
 *   <li>The verdict-false guard inside {@code RetryPolicy.failoverable} is a
 *       documented surviving mutant — moved verbatim, never "fixed", never
 *       tested.</li>
 * </ol>
 */
public final class Transport {
    /** Which base URL family a request routes through. */
    public enum Plane { INFERENCE, CONTROL }

    private final String baseUrl;
    private final List<String> inferenceBaseUrls;
    private final String controlBaseUrl;
    private final boolean regionalFailover;
    private final RetryPolicy retryPolicy;
    private final RequestFactory requestFactory;
    private final Sleeper sleeper;

    public Transport(TrustedRouterOptions options) {
        this.baseUrl = options.getBaseUrl();
        this.controlBaseUrl = options.getControlBaseUrl();
        this.regionalFailover = options.isRegionalFailover();
        this.inferenceBaseUrls = CandidateUrls.inferenceBaseUrls(this.baseUrl, this.regionalFailover);
        this.retryPolicy = new RetryPolicy(options.getMaxRetries());
        this.requestFactory = new RequestFactory(options);
        this.sleeper = new JitterSleeper();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getControlBaseUrl() {
        return controlBaseUrl;
    }

    /** Delegates to {@link CandidateUrls#inferenceBaseUrls}; kept for existing imports. */
    public static List<String> inferenceBaseUrls(
            String primaryBaseUrl, boolean regionalFailover) {
        return CandidateUrls.inferenceBaseUrls(primaryBaseUrl, regionalFailover);
    }

    public Response execute(
            Plane plane,
            String method,
            String path,
            JsonElement body,
            CallOptions options,
            boolean streaming) throws TrustedRouterException {
        boolean inference = plane == Plane.INFERENCE;
        List<String> bases = inference
                ? inferenceBaseUrls : Collections.singletonList(controlBaseUrl);
        return executeUrls(
                CandidateUrls.joinAll(bases, path), method, body, normalize(options), streaming,
                inference ? regionalFailover : true, true);
    }

    public Response executeAbsolute(String url, String method, boolean streaming)
            throws TrustedRouterException {
        // A singleton URL list: the structural gate below makes failover
        // impossible for absolute fetches no matter what the flag says.
        return executeUrls(
                Collections.singletonList(url), method, null, CallOptions.NONE, streaming,
                true, false);
    }

    /**
     * The single loop. Per iteration it extracts {@code AttemptFacts} from
     * the outcome (before closing any response), asks the L1 kernel for a
     * {@code RetryDecision}, and executes it. The candidate index advances
     * in exactly ONE place, gated structurally by the list length.
     */
    private Response executeUrls(
            List<String> urls,
            String method,
            JsonElement body,
            CallOptions options,
            boolean streaming,
            boolean allowRegionalFailover,
            boolean includeCredentials) throws TrustedRouterException {
        int attempt = 0;
        int baseIndex = 0;
        while (true) {
            String url = urls.get(baseIndex);
            Response response = null;
            IOException failure = null;
            RetryPolicy.AttemptFacts facts;
            try {
                response = requestFactory.requestClient(options, streaming)
                        .newCall(requestFactory.buildRequest(
                                url, method, body, options, includeCredentials))
                        .execute();
                // All facts are read while the response is still open.
                facts = RetryPolicy.AttemptFacts.httpResponse(
                        response.code(),
                        response.header("x-should-retry"),
                        ErrorClassifier.retryAfterSeconds(response),
                        allowRegionalFailover);
            } catch (IOException error) {
                failure = error;
                facts = RetryPolicy.AttemptFacts.ioFailure(allowRegionalFailover);
            }
            RetryPolicy.RetryDecision decision = retryPolicy.decide(attempt, facts);
            if (decision.getKind() == RetryPolicy.RetryDecision.Kind.RETURN_RESPONSE) {
                return response;
            }
            if (decision.getKind() == RetryPolicy.RetryDecision.Kind.THROW) {
                throw new InternalException(
                        503,
                        "TrustedRouter endpoint unavailable: " + failure.getMessage(),
                        null,
                        failure);
            }
            if (response != null) {
                response.close();
            }
            // THE single advance site: the only place a candidate index moves.
            if (decision.isMoveHost() && baseIndex + 1 < urls.size()) {
                baseIndex++;
            }
            sleeper.sleep(attempt, decision.getRetryAfterSeconds());
            attempt++;
        }
    }

    /** Delegates to {@link ErrorClassifier#decodeJson}; kept for existing imports. */
    public static JsonElement decodeJson(Response response) throws TrustedRouterException {
        return ErrorClassifier.decodeJson(response);
    }

    /** Delegates to {@link ErrorClassifier#requireSuccess}; kept for existing imports. */
    public static void requireSuccess(Response response) throws TrustedRouterException {
        ErrorClassifier.requireSuccess(response);
    }

    private static CallOptions normalize(CallOptions options) {
        return options == null ? CallOptions.NONE : options;
    }
}
