package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
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
 * <p>Telemetry (client telemetry contract v1): INFERENCE-plane calls with
 * telemetry enabled get a {@link RequestRecorder} that observes every
 * attempt in {@link #executeUrls} (the single emit point, &sect;6.1) and is
 * finished here for buffered calls, or handed to the stream wrapper by
 * {@link #executeStream} so TTFT, mid-body failures, and caller aborts are
 * observed before it finishes. The recorder's sink is the
 * {@link TelemetryReporter} owned by this transport: created lazily on the
 * first recorded call, never at construction, and never for control-plane
 * calls, absolute fetches, or an opted-out client. The reporter's beacon
 * POST uses its own single-shot client and NEVER enters this loop.
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
 *   <li>High-level mutations mint one idempotency key before the loop; generic
 *       mutations replay only when the caller supplies one. Any key is re-sent
 *       verbatim across every attempt and domain move —
 *       {@code ClientTransportTest.retriesRateLimitAndPreservesIdempotencyKey}.</li>
 *   <li>Retries happen only before any body bytes are surfaced; a broken
 *       open stream propagates, never reconnects ({@code EventStream} has no
 *       reconnect path) —
 *       {@code StreamingTest.unexpectedEofCannotMasqueradeAsACompletedStream}.</li>
 *   <li>The failover flag governs WHERE, never WHETHER — a pinned client
 *       still retries in place —
 *       {@code ShouldRetryHeaderTest.aPinnedClientStillRetriesInPlace}.</li>
 *   <li>Replay-safe transport errors may move hosts within the flag gating;
 *       HTTP moves additionally require a failoverable status —
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

    private static final ThreadLocal<CancellationToken> CANCELLATION =
            new ThreadLocal<CancellationToken>();

    /** Internal cancellation bridge used by the CompletableFuture facade. */
    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> call = new AtomicReference<Call>();

        public void cancel() {
            cancelled.set(true);
            Call active = call.get();
            if (active != null) {
                active.cancel();
            }
        }

        boolean isCancelled() { return cancelled.get(); }
        void attach(Call value) {
            call.set(value);
            if (cancelled.get()) {
                value.cancel();
            }
        }

        /**
         * Releases the last physical call after the complete high-level
         * supplier has finished consuming (or handing off) its response.
         * Keeping the call attached beyond {@code Call.execute()} is what
         * lets cancellation close a socket while a buffered body is read.
         */
        public void clear() { call.set(null); }
    }

    /** Binds a token to requests made on the current worker thread. */
    public static void bindCancellation(CancellationToken token) {
        CANCELLATION.set(token);
    }

    /** Clears the current worker's cancellation token. */
    public static void clearCancellation() {
        CANCELLATION.remove();
    }

    /**
     * A successfully opened stream plus the recorder its wrapper must drive
     * to completion, or null when nothing is recorded (telemetry off, or
     * the open failed and the recorder already finished).
     */
    public static final class OpenedStream {
        private final Response response;
        private final RequestRecorder recorder;

        OpenedStream(Response response, RequestRecorder recorder) {
            this.response = response;
            this.recorder = recorder;
        }

        public Response response() {
            return response;
        }

        public RequestRecorder recorder() {
            return recorder;
        }

        /**
         * Finishes the recorder for a stream the caller could not wrap:
         * the attempt becomes {@code stream_broken} with the given cause.
         */
        public void abandon(IOException failure) {
            if (recorder != null) {
                recorder.onTransportError(failure, true, false);
                recorder.finish();
            }
        }
    }

    private static final class Attempted {
        private final Response response;
        private final RequestRecorder recorder;

        private Attempted(Response response, RequestRecorder recorder) {
            this.response = response;
            this.recorder = recorder;
        }
    }

    private final TrustedRouterOptions options;
    private final String baseUrl;
    private final List<String> inferenceBaseUrls;
    private final String controlBaseUrl;
    private final boolean regionalFailover;
    private final boolean telemetryEnabled;
    private final RetryPolicy retryPolicy;
    private final RequestFactory requestFactory;
    private final Sleeper sleeper;
    private final Object telemetryLock = new Object();
    private volatile TelemetryReporter reporter;

    public Transport(TrustedRouterOptions options) {
        this.options = options;
        this.baseUrl = options.getBaseUrl();
        this.controlBaseUrl = options.getControlBaseUrl();
        this.regionalFailover = options.isRegionalFailover();
        this.inferenceBaseUrls = CandidateUrls.inferenceBaseUrls(this.baseUrl, this.regionalFailover);
        this.telemetryEnabled = Telemetry.resolveEnabled(
                options.getTelemetry(), this.baseUrl, this.controlBaseUrl);
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

    /**
     * Executes one logical call and finishes its telemetry record as soon as
     * the attempt loop returns. Streaming callers that want TTFT, mid-body
     * failure, and abort facts use {@link #executeStream} instead.
     */
    public Response execute(
            Plane plane,
            String method,
            String path,
            JsonElement body,
            CallOptions options,
            boolean streaming) throws TrustedRouterException {
        Attempted attempted = attempt(plane, method, path, body, options, streaming);
        if (attempted.recorder != null) {
            attempted.recorder.finish();
        }
        return attempted.response;
    }

    /**
     * Opens a stream. A non-2xx open finishes the record here (the caller
     * classifies the status); a 2xx hands the live recorder to the stream
     * wrapper, which finishes it on [DONE], mid-body failure, or close.
     */
    public OpenedStream executeStream(
            Plane plane,
            String method,
            String path,
            JsonElement body,
            CallOptions options) throws TrustedRouterException {
        Attempted attempted = attempt(plane, method, path, body, options, true);
        RequestRecorder recorder = attempted.recorder;
        if (recorder != null && !attempted.response.isSuccessful()) {
            recorder.finish();
            recorder = null;
        }
        return new OpenedStream(attempted.response, recorder);
    }

    private Attempted attempt(
            Plane plane,
            String method,
            String path,
            JsonElement body,
            CallOptions options,
            boolean streaming) throws TrustedRouterException {
        boolean inference = plane == Plane.INFERENCE;
        List<String> bases = inference
                ? inferenceBaseUrls : Collections.singletonList(controlBaseUrl);
        CallOptions normalized = normalize(options);
        // Telemetry records INFERENCE calls only: control-plane calls get no
        // x-tr-client header, no recorder activity, and no beacon (§3.2, §6.2).
        RequestRecorder recorder = inference && telemetryEnabled
                ? newRecorder(method, path, body, normalized, streaming) : null;
        try {
            Response response = executeUrls(
                    CandidateUrls.joinAll(bases, path), method, body, normalized, streaming,
                    inference ? regionalFailover : true, true, recorder);
            return new Attempted(response, recorder);
        } catch (TrustedRouterException | RuntimeException error) {
            if (recorder != null) {
                if (isAbort(error)) {
                    recorder.onAborted();
                }
                recorder.finish();
            }
            throw error;
        }
    }

    /**
     * Builds the recorder for one inference call. Never throws (&sect;2.2):
     * an unbuildable recorder means the call simply goes unrecorded.
     */
    private RequestRecorder newRecorder(
            String method, String path, JsonElement body, CallOptions options,
            boolean streaming) {
        try {
            OkHttpClient client = requestFactory.requestClient(options, streaming);
            RequestRecorder.ConfiguredTimeouts timeouts = new RequestRecorder.ConfiguredTimeouts(
                    positive(client.connectTimeoutMillis()),
                    positive(client.readTimeoutMillis()),
                    positive(client.callTimeoutMillis()),
                    requestFactory.sdkTimeoutMillis(options));
            return new RequestRecorder(
                    reporter(), Telemetry.endpointEnum(path), method, streaming,
                    providerPinned(body), modelOf(body), timeouts, null);
        } catch (RuntimeException impossible) {
            return null;
        }
    }

    /**
     * The beacon reporter, created on the first recorded call and shared by
     * every later one. Its worker thread starts on its own first record.
     */
    private TelemetryReporter reporter() {
        TelemetryReporter current = reporter;
        if (current == null) {
            synchronized (telemetryLock) {
                current = reporter;
                if (current == null) {
                    current = TelemetryReporter.builder()
                            .controlBaseUrl(controlBaseUrl)
                            .apiKey(options::getApiKey)
                            .workspaceId(options.getWorkspaceId())
                            .successSampleRate(options.getTelemetrySampleRate())
                            .build();
                    reporter = current;
                }
            }
        }
        return current;
    }

    /** The beacon reporter once an inference call was recorded, else null. */
    public TelemetryReporter telemetryReporter() {
        return reporter;
    }

    /**
     * Flushes pending telemetry once, bounded by 2 s, and stops the beacon
     * worker. Safe to call more than once and without any telemetry.
     */
    public void close() {
        TelemetryReporter current = reporter;
        if (current != null) {
            current.close(Telemetry.FINAL_FLUSH_MS);
        }
    }

    private static Long positive(int millis) {
        return millis > 0 ? Long.valueOf(millis) : null;
    }

    /** Whether the body pinned a provider: {@code provider.allow_fallbacks == false} (as in Python). */
    static boolean providerPinned(JsonElement body) {
        if (body == null || !body.isJsonObject()) {
            return false;
        }
        JsonElement provider = body.getAsJsonObject().get("provider");
        if (provider == null || !provider.isJsonObject()) {
            return false;
        }
        JsonElement allowFallbacks = provider.getAsJsonObject().get("allow_fallbacks");
        return allowFallbacks != null && allowFallbacks.isJsonPrimitive()
                && allowFallbacks.getAsJsonPrimitive().isBoolean()
                && !allowFallbacks.getAsBoolean();
    }

    /** The body's model string, or null; the recorder applies the grammar. */
    static String modelOf(JsonElement body) {
        if (body == null || !body.isJsonObject()) {
            return null;
        }
        JsonObject object = body.getAsJsonObject();
        JsonElement model = object.get("model");
        return model != null && model.isJsonPrimitive() && model.getAsJsonPrimitive().isString()
                ? model.getAsString() : null;
    }

    /**
     * Whether a failure is the caller's doing — cancellation through the
     * async facade (status 499) or thread interruption during backoff —
     * rather than the network's, so the attempt is {@code aborted}.
     */
    static boolean isAbort(Throwable error) {
        if (error instanceof TrustedRouterException
                && ((TrustedRouterException) error).getStatusCode() == 499) {
            return true;
        }
        for (Throwable item : Telemetry.causeChain(error)) {
            if (item instanceof InterruptedIOException && !Telemetry.isTimeout(item)) {
                return true;
            }
        }
        return false;
    }

    public Response executeAbsolute(String url, String method, boolean streaming)
            throws TrustedRouterException {
        // A singleton URL list: the structural gate below makes failover
        // impossible for absolute fetches no matter what the flag says.
        // Absolute metadata fetches are never telemetered: no recorder.
        return executeUrls(
                Collections.singletonList(url), method, null, CallOptions.NONE, streaming,
                true, false, null);
    }

    /** Executes a credential-free, single-origin control-plane request. */
    public Response executeCredentialFreeControl(
            String method, String path, JsonElement body, boolean streaming)
            throws TrustedRouterException {
        return executeUrls(
                Collections.singletonList(CandidateUrls.joinUrl(controlBaseUrl, path)),
                method, body, CallOptions.NONE, streaming, false, false, null);
    }

    /**
     * The single loop. Per iteration it extracts {@code AttemptFacts} from
     * the outcome (before closing any response), asks the L1 kernel for a
     * {@code RetryDecision}, and executes it. The candidate index advances
     * in exactly ONE place, gated structurally by the list length.
     *
     * <p>This is also the SDK's single telemetry emit point (contract §6.1):
     * a non-null {@code recorder} observes each attempt here and derives the
     * next attempt's {@code x-tr-client} header, which the request factory
     * stamps. A null recorder — control plane, absolute fetches, telemetry
     * off — means no header and no recorder activity anywhere.
     */
    private Response executeUrls(
            List<String> urls,
            String method,
            JsonElement body,
            CallOptions options,
            boolean streaming,
            boolean allowRegionalFailover,
            boolean includeCredentials,
            RequestRecorder recorder) throws TrustedRouterException {
        int attempt = 0;
        int baseIndex = 0;
        boolean replayable = isReplayable(method, options);
        while (true) {
            CancellationToken cancellation = CANCELLATION.get();
            if (cancellation != null && cancellation.isCancelled()) {
                throw new InternalException(499, "TrustedRouter request cancelled", null);
            }
            String url = urls.get(baseIndex);
            String telemetryHeader = null;
            if (recorder != null) {
                recorder.beginAttempt(url);
                telemetryHeader = recorder.headerValue();
            }
            Response response = null;
            IOException failure = null;
            RetryPolicy.AttemptFacts facts;
            Call call = null;
            try {
                call = requestFactory.requestClient(options, streaming)
                        .newCall(requestFactory.buildRequest(
                                url, method, body, options, includeCredentials,
                                telemetryHeader));
                if (cancellation != null) {
                    cancellation.attach(call);
                }
                response = call.execute();
                // All facts are read while the response is still open.
                facts = RetryPolicy.AttemptFacts.httpResponse(
                        response.code(),
                        response.header("x-should-retry"),
                        ErrorClassifier.retryAfterSeconds(response),
                        allowRegionalFailover,
                        replayable);
                if (recorder != null) {
                    recorder.onResponse(response.code(), response);
                }
            } catch (IOException error) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new InternalException(
                            499, "TrustedRouter request cancelled", null, error);
                }
                failure = error;
                facts = RetryPolicy.AttemptFacts.ioFailure(
                        allowRegionalFailover, replayable);
                if (recorder != null) {
                    // Classified HERE, from the live exception type and cause
                    // chain: the THROW branch below flattens it into an
                    // InternalException message string (contract §6.1).
                    recorder.onTransportError(error);
                }
            }
            RetryPolicy.RetryDecision decision = retryPolicy.decide(attempt, facts);
            if (decision.getKind() == RetryPolicy.RetryDecision.Kind.RETURN_RESPONSE) {
                // Exhausted (§5.3): a replayable retry beyond the first
                // attempt ended retryable with no budget left. A
                // non-replayable response returns before any retry question
                // is asked, exactly as the Python reference.
                if (recorder != null && attempt > 0 && facts.isReplayable()
                        && RetryPolicy.retryable(facts.getStatus(), facts.getShouldRetryVerdict())) {
                    recorder.markExhausted(true);
                }
                return response;
            }
            if (decision.getKind() == RetryPolicy.RetryDecision.Kind.THROW) {
                if (recorder != null && attempt > 0 && facts.isReplayable()) {
                    recorder.markExhausted(true);
                }
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
                if (recorder != null) {
                    recorder.onMoved();
                }
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

    private static boolean isReplayable(String method, CallOptions options) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if ("GET".equals(normalized) || "HEAD".equals(normalized)
                || "OPTIONS".equals(normalized)) {
            return true;
        }
        String key = options.getIdempotencyKey();
        return key != null && !key.isEmpty();
    }
}
