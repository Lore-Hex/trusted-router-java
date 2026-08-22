package com.trustedrouter.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.Response;

/**
 * Records one logical inference call across the retry/failover loop, derives
 * the per-attempt {@code x-tr-client} header (contract v1, &sect;3.2), and on
 * {@link #finish()} hands the call's event and exact counter increments to
 * a {@link TelemetrySink} (&sect;5.3/&sect;5.4). Internal class with no
 * compatibility guarantees; mirrors the Python SDK's {@code RequestRecorder}.
 *
 * <p>One recorder is created per logical call by the transport engine — for
 * INFERENCE-plane calls only, and only when telemetry resolved enabled — and
 * threaded through {@code Transport.executeUrls}, the single emit point
 * (&sect;6.1). Streaming calls hand the recorder to the stream wrapper,
 * which reports the first event (TTFT), mid-body failures, and caller aborts
 * before finishing it. Control-plane and absolute fetches never construct
 * one, so they produce no header, no event, and no counters.
 *
 * <p>Telemetry never fails a request (&sect;2.2): every value below comes
 * from a closed internal vocabulary or clamped long arithmetic, every hook
 * swallows its own failures, and {@link #headerValue()} re-validates the
 * grammar and byte cap anyway, sending NOTHING rather than ever throwing.
 */
public final class RequestRecorder {
    /** One attempt's facts; mutable internal data, never exposed publicly. */
    public static final class AttemptRecord {
        public int index;
        public String host;
        public String outcome;
        public String errorClass;
        public long elapsedMs;
        public boolean moved;
        /** HTTP status when a response arrived (&sect;5.3), else null. */
        public Integer httpStatus;
        /** Error source from the error body, always null today (mirrors Python). */
        public String errorSource;
        /** {@code true}, {@code false}, or {@code absent} — x-should-retry as observed. */
        public String shouldRetry = "absent";
        public Long retryAfterMs;
        /** Milliseconds until response headers, else null. */
        public Long ttfbMs;
        /** The enclave's {@code x-request-id} when in grammar, else null. */
        public String requestId;

        AttemptRecord copy() {
            AttemptRecord copy = new AttemptRecord();
            copy.index = index;
            copy.host = host;
            copy.outcome = outcome;
            copy.errorClass = errorClass;
            copy.elapsedMs = elapsedMs;
            copy.moved = moved;
            copy.httpStatus = httpStatus;
            copy.errorSource = errorSource;
            copy.shouldRetry = shouldRetry;
            copy.retryAfterMs = retryAfterMs;
            copy.ttfbMs = ttfbMs;
            copy.requestId = requestId;
            return copy;
        }
    }

    /** Monotonic clock seam so tests can pin durations; production uses nanoTime. */
    public interface NanoClock {
        long nanos();
    }

    /**
     * The timeouts configured for one call, by contract phase (&sect;5.3
     * {@code configured_timeout_ms}): OkHttp's connect timeout for
     * {@code connect}, its read timeout for {@code first_byte}/{@code idle},
     * its whole-call timeout for {@code total}, and the SDK-level call
     * timeout for {@code none}. Zero or negative means unconfigured (null).
     */
    public static final class ConfiguredTimeouts {
        /** No timeout configured in any phase. */
        public static final ConfiguredTimeouts NONE = new ConfiguredTimeouts(null, null, null, null);

        private final Long connectMs;
        private final Long readMs;
        private final Long callMs;
        private final Long sdkMs;

        public ConfiguredTimeouts(Long connectMs, Long readMs, Long callMs, Long sdkMs) {
            this.connectMs = connectMs;
            this.readMs = readMs;
            this.callMs = callMs;
            this.sdkMs = sdkMs;
        }

        /** The configured timeout for a phase in {@code [1, 3600000]}, or null. */
        public Long forPhase(String phase) {
            Long value;
            if ("connect".equals(phase)) {
                value = connectMs;
            } else if ("first_byte".equals(phase) || "idle".equals(phase)) {
                value = readMs;
            } else if ("total".equals(phase)) {
                value = callMs;
            } else {
                value = sdkMs;
            }
            if (value == null || value.longValue() <= 0L) {
                return null;
            }
            return Long.valueOf(Math.min(Telemetry.MAX_DURATION_MS, value.longValue()));
        }
    }

    /** One finished logical call, as handed to the sink (the Python {@code _finish} event). */
    public static final class Event {
        public final String endpoint;
        public final String method;
        public final boolean streaming;
        public final boolean providerPinned;
        public final String model;
        public final List<AttemptRecord> attempts;
        public final String finalOutcome;
        public final Integer finalHttpStatus;
        public final long totalMs;
        public final Long ttftMs;
        public final boolean failoverUsed;
        public final String timeoutPhase;
        public final Long configuredTimeoutMs;

        Event(
                String endpoint,
                String method,
                boolean streaming,
                boolean providerPinned,
                String model,
                List<AttemptRecord> attempts,
                String finalOutcome,
                Integer finalHttpStatus,
                long totalMs,
                Long ttftMs,
                boolean failoverUsed,
                String timeoutPhase,
                Long configuredTimeoutMs) {
            this.endpoint = endpoint;
            this.method = method;
            this.streaming = streaming;
            this.providerPinned = providerPinned;
            this.model = model;
            this.attempts = Collections.unmodifiableList(attempts);
            this.finalOutcome = finalOutcome;
            this.finalHttpStatus = finalHttpStatus;
            this.totalMs = totalMs;
            this.ttftMs = ttftMs;
            this.failoverUsed = failoverUsed;
            this.timeoutPhase = timeoutPhase;
            this.configuredTimeoutMs = configuredTimeoutMs;
        }
    }

    /**
     * The exact 10-field counter key (&sect;5.4): everything on a
     * {@code ClientMinuteCounter} minus the counts and histograms — and
     * deliberately not the model. Value semantics; field order is the
     * Python tuple order.
     */
    public static final class CounterKey {
        public final String level;
        public final String endpoint;
        public final boolean streaming;
        public final String host;
        public final String outcome;
        public final String errorClass;
        public final String httpStatusClass;
        public final String timeoutPhase;
        public final boolean timeoutFloorMet;
        public final boolean providerPinned;

        public CounterKey(
                String level,
                String endpoint,
                boolean streaming,
                String host,
                String outcome,
                String errorClass,
                String httpStatusClass,
                String timeoutPhase,
                boolean timeoutFloorMet,
                boolean providerPinned) {
            this.level = level;
            this.endpoint = endpoint;
            this.streaming = streaming;
            this.host = host;
            this.outcome = outcome;
            this.errorClass = errorClass;
            this.httpStatusClass = httpStatusClass;
            this.timeoutPhase = timeoutPhase;
            this.timeoutFloorMet = timeoutFloorMet;
            this.providerPinned = providerPinned;
        }

        /** The same key with a different error class (the first fold rung). */
        public CounterKey withErrorClass(String value) {
            return new CounterKey(level, endpoint, streaming, host, outcome, value,
                    httpStatusClass, timeoutPhase, timeoutFloorMet, providerPinned);
        }

        /** The same key with a different endpoint (the second fold rung). */
        public CounterKey withEndpoint(String value) {
            return new CounterKey(level, value, streaming, host, outcome, errorClass,
                    httpStatusClass, timeoutPhase, timeoutFloorMet, providerPinned);
        }

        /** Equality on every field except the error class. */
        public boolean matchesExceptErrorClass(CounterKey other) {
            return level.equals(other.level)
                    && endpoint.equals(other.endpoint)
                    && matchesEndpointFree(other);
        }

        /** Equality on every field except the endpoint and the error class. */
        public boolean matchesExceptEndpointAndErrorClass(CounterKey other) {
            return level.equals(other.level) && matchesEndpointFree(other);
        }

        private boolean matchesEndpointFree(CounterKey other) {
            return streaming == other.streaming
                    && host.equals(other.host)
                    && outcome.equals(other.outcome)
                    && httpStatusClass.equals(other.httpStatusClass)
                    && timeoutPhase.equals(other.timeoutPhase)
                    && timeoutFloorMet == other.timeoutFloorMet
                    && providerPinned == other.providerPinned;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CounterKey)) {
                return false;
            }
            CounterKey that = (CounterKey) other;
            return matchesExceptErrorClass(that)
                    && (errorClass == null ? that.errorClass == null
                            : errorClass.equals(that.errorClass));
        }

        @Override
        public int hashCode() {
            int result = level.hashCode();
            result = 31 * result + endpoint.hashCode();
            result = 31 * result + (streaming ? 1 : 0);
            result = 31 * result + host.hashCode();
            result = 31 * result + outcome.hashCode();
            result = 31 * result + (errorClass == null ? 0 : errorClass.hashCode());
            result = 31 * result + httpStatusClass.hashCode();
            result = 31 * result + timeoutPhase.hashCode();
            result = 31 * result + (timeoutFloorMet ? 1 : 0);
            result = 31 * result + (providerPinned ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "(" + level + ", " + endpoint + ", " + streaming + ", " + host + ", "
                    + outcome + ", " + errorClass + ", " + httpStatusClass + ", "
                    + timeoutPhase + ", " + timeoutFloorMet + ", " + providerPinned + ")";
        }
    }

    /** Counts and histograms for one counter key (&sect;5.4); mutable merge target. */
    public static final class CounterIncrement {
        public long requests;
        public long attempts;
        public long failoverUsed;
        public long firstAttemptSuccess;
        public final Map<String, Long> totalMsHist = new LinkedHashMap<String, Long>();
        public final Map<String, Long> firstEventMsHist = new LinkedHashMap<String, Long>();

        public CounterIncrement() {}

        public CounterIncrement(
                long requests, long attempts, long failoverUsed, long firstAttemptSuccess) {
            this.requests = requests;
            this.attempts = attempts;
            this.failoverUsed = failoverUsed;
            this.firstAttemptSuccess = firstAttemptSuccess;
        }

        /** Adds one observation to a histogram. */
        public CounterIncrement bucket(Map<String, Long> histogram, String bucket) {
            Long current = histogram.get(bucket);
            histogram.put(bucket, Long.valueOf(current == null ? 1L : current.longValue() + 1L));
            return this;
        }

        public CounterIncrement copy() {
            CounterIncrement copy = new CounterIncrement(
                    requests, attempts, failoverUsed, firstAttemptSuccess);
            copy.totalMsHist.putAll(totalMsHist);
            copy.firstEventMsHist.putAll(firstEventMsHist);
            return copy;
        }

        @Override
        public String toString() {
            return "{requests=" + requests + ", attempts=" + attempts + ", failover_used="
                    + failoverUsed + ", first_attempt_success=" + firstAttemptSuccess
                    + ", total_ms_hist=" + totalMsHist + ", first_event_ms_hist="
                    + firstEventMsHist + "}";
        }
    }

    /** One counter key with its increment, as emitted by {@link #finish()}. */
    public static final class CounterUpdate {
        public final CounterKey key;
        public final CounterIncrement increment;

        public CounterUpdate(CounterKey key, CounterIncrement increment) {
            this.key = key;
            this.increment = increment;
        }
    }

    private static final NanoClock SYSTEM_CLOCK = new NanoClock() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };

    private final TelemetrySink sink;
    private final String endpoint;
    private final String method;
    private final boolean recordable;
    private final boolean streaming;
    private final boolean providerPinned;
    private final String model;
    private final ConfiguredTimeouts timeouts;
    private final NanoClock clock;
    private final List<AttemptRecord> attempts = new ArrayList<AttemptRecord>();
    private final List<String> phases = new ArrayList<String>();
    private boolean failoverUsed;
    private Long ttftMs;
    private boolean started;
    private long firstStartedNanos;
    private long attemptStartedNanos;
    private String currentHost;
    private int currentIndex = -1;
    private boolean exhausted;
    private boolean finished;

    /** Header-only recorder (no sink): nothing is emitted on finish. */
    public RequestRecorder(boolean streaming) {
        this(streaming, SYSTEM_CLOCK);
    }

    /** Header-only recorder with an injected clock. */
    public RequestRecorder(boolean streaming, NanoClock clock) {
        this(null, "inference_other", "POST", streaming, false, null, null, clock);
    }

    /**
     * Full recorder for one logical inference call.
     *
     * @param sink where the finished call goes; null records the header only
     * @param endpoint the {@link Telemetry#endpointEnum} of the path
     * @param method the HTTP method; only GET and POST are emitted
     *     ({@link Telemetry#BEACON_METHODS})
     * @param providerPinned whether the body pinned a provider
     *     ({@code provider.allow_fallbacks == false}, as in Python)
     * @param model the body's model, sent only when in grammar
     * @param timeouts the call's configured timeouts by phase
     */
    public RequestRecorder(
            TelemetrySink sink,
            String endpoint,
            String method,
            boolean streaming,
            boolean providerPinned,
            String model,
            ConfiguredTimeouts timeouts,
            NanoClock clock) {
        this.sink = sink;
        this.endpoint = Telemetry.ENDPOINTS.contains(endpoint) ? endpoint : "inference_other";
        this.method = method == null ? "" : method.toUpperCase(Locale.ROOT);
        this.recordable = Telemetry.BEACON_METHODS.contains(this.method);
        this.streaming = streaming;
        this.providerPinned = providerPinned;
        this.model = model != null && Telemetry.MODEL.matcher(model).matches() ? model : null;
        this.timeouts = timeouts == null ? ConfiguredTimeouts.NONE : timeouts;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
    }

    /** Marks the start of the next attempt against the given candidate URL. */
    public void beginAttempt(String url) {
        try {
            long startedNanos = clock.nanos();
            if (!started) {
                started = true;
                firstStartedNanos = startedNanos;
            }
            attemptStartedNanos = startedNanos;
            currentHost = Telemetry.hostEnum(url);
            currentIndex = attempts.size();
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2); an unrecordable attempt
            // simply produces no header.
            currentHost = null;
            currentIndex = -1;
        }
    }

    /**
     * The {@code x-tr-client} value for the attempt begun last, in the exact
     * key order {@code v,a[,po,pc,ph,pm,sm],s[,fo]} — the bracketed keys only
     * from the second attempt on. Returns null — send nothing — for a custom
     * attempt host (&sect;3.2: a self-hosted gateway is not TrustedRouter's
     * to measure), before any attempt began, or if any value fails the
     * anchored grammar or the 160-byte cap. Never throws.
     */
    public String headerValue() {
        try {
            if (currentIndex < 0 || currentHost == null || "custom".equals(currentHost)) {
                return null;
            }
            if (currentIndex > 99) {
                // §3.2 bounds a to 0..99; a three-digit index would pass the
                // value regex but fail the enclave's semantic validation, so
                // send nothing instead of a header that only costs a dropped
                // line. (The Python reference shares this latent overflow;
                // flagged upstream.)
                return null;
            }
            List<String> values = new ArrayList<String>();
            values.add("v=1");
            values.add("a=" + currentIndex);
            if (currentIndex > 0) {
                AttemptRecord previous = attempts.get(attempts.size() - 1);
                long sinceFirstMs = Telemetry.clampDurationMs(
                        (attemptStartedNanos - firstStartedNanos) / 1_000_000L);
                // §3.2 closes po over {none, http_error, transport_error,
                // timeout, stream_broken}. A forced retry after a sub-400
                // response (x-should-retry: true) records outcome "ok",
                // which is OUTSIDE that vocabulary — the enclave would drop
                // the whole header. Cross-SDK ruling: map any out-of-vocab
                // previous outcome to po=none with pc=none. (The Python
                // reference shares the po=ok bug; upstream issue filed.)
                String previousOutcome = previous.outcome;
                String previousErrorClass =
                        previous.errorClass == null ? "none" : previous.errorClass;
                boolean inVocabulary = "http_error".equals(previousOutcome)
                        || "transport_error".equals(previousOutcome)
                        || "timeout".equals(previousOutcome)
                        || "stream_broken".equals(previousOutcome);
                if (!inVocabulary) {
                    previousOutcome = "none";
                    previousErrorClass = "none";
                }
                values.add("po=" + previousOutcome);
                values.add("pc=" + previousErrorClass);
                values.add("ph=" + previous.host);
                values.add("pm=" + previous.elapsedMs);
                values.add("sm=" + sinceFirstMs);
            }
            values.add("s=" + (streaming ? 1 : 0));
            if (currentIndex > 0) {
                values.add("fo=" + (failoverUsed ? 1 : 0));
            }
            StringBuilder header = new StringBuilder();
            for (String part : values) {
                if (header.length() > 0) {
                    header.append(';');
                }
                header.append(part);
                String value = part.substring(part.indexOf('=') + 1);
                if (!Telemetry.HEADER_VALUE.matcher(value).matches()) {
                    return null;
                }
            }
            // Bounded by construction (enum values, ms <= 7 digits, <= 9
            // keys), but telemetry may never raise on the request path, so an
            // out-of-grammar value simply sends nothing (&sect;2.2). Every
            // grammar character is one byte, so length() is the byte count.
            if (header.length() > Telemetry.MAX_HEADER_BYTES) {
                return null;
            }
            return header.toString();
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (&sect;2.2).
            return null;
        }
    }

    /** Records an attempt that produced an HTTP response, headers unknown. */
    public void onResponse(int statusCode) {
        onResponse(statusCode, null);
    }

    /**
     * Records an attempt that produced an HTTP response, reading the
     * {@code x-should-retry} verdict, the retry-after hint, and the
     * enclave's {@code x-request-id} from the live headers (&sect;3.3/&sect;5.3).
     * Must run before the engine closes the response.
     */
    public void onResponse(int statusCode, Response response) {
        try {
            if (currentIndex < 0 || currentHost == null) {
                return;
            }
            AttemptRecord attempt = new AttemptRecord();
            attempt.outcome = statusCode < 400 ? "ok" : "http_error";
            attempt.httpStatus = Integer.valueOf(statusCode);
            attempt.errorClass = null;
            attempt.errorSource = null;
            attempt.shouldRetry = "absent";
            if (response != null) {
                String verdict = response.header("x-should-retry");
                if (verdict != null) {
                    String lowered = verdict.trim().toLowerCase(Locale.ROOT);
                    if ("true".equals(lowered) || "false".equals(lowered)) {
                        attempt.shouldRetry = lowered;
                    }
                }
                Double retryAfter = ErrorClassifier.retryAfterSeconds(response);
                if (retryAfter != null) {
                    attempt.retryAfterMs = Long.valueOf(Telemetry.clampDurationMs(
                            (long) (retryAfter.doubleValue() * 1000.0d)));
                }
                String requestId = response.header("x-request-id");
                if (requestId != null && Telemetry.REQUEST_ID.matcher(requestId).matches()) {
                    attempt.requestId = requestId;
                }
            }
            attempt.elapsedMs = elapsedSinceAttemptStart();
            attempt.ttfbMs = Long.valueOf(attempt.elapsedMs);
            store(attempt, "none");
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /**
     * Records an attempt where the HTTP client threw before any response.
     * Must be called at the engine's {@code IOException} catch, BEFORE the
     * failure is flattened into an {@code InternalException} message string
     * (&sect;6.1). A hostile exception subtype (throwing {@code getCause()}
     * or {@code getMessage()}) must not replace the engine's retry decision,
     * so the whole hook fails open.
     */
    public void onTransportError(IOException error) {
        onTransportError(error, false, false);
    }

    /**
     * Records a transport failure at any point of the attempt, mirroring the
     * Python SDK's {@code on_transport_error}: a timeout is {@code timeout}
     * (re-phased to {@code idle}, class {@code stream_stalled} for a read
     * stall, once body bytes were surfaced); any other failure after the
     * first body byte is {@code stream_broken}; before that it is
     * {@code transport_error}. A failure after the response opened keeps the
     * response's status, TTFB, and request id on the record.
     *
     * @param responseOpened whether response headers had arrived
     * @param bodyStarted whether the first body event had been surfaced
     */
    public void onTransportError(Throwable error, boolean responseOpened, boolean bodyStarted) {
        try {
            if (currentIndex < 0 || currentHost == null) {
                return;
            }
            String errorClass = Telemetry.classifyTransportError(error);
            String phase = Telemetry.timeoutPhase(error);
            String outcome;
            if (Telemetry.isTimeout(error)) {
                outcome = "timeout";
                if (bodyStarted) {
                    phase = "idle";
                    if ("read_timeout".equals(errorClass)) {
                        errorClass = "stream_stalled";
                    }
                }
            } else if (bodyStarted) {
                outcome = "stream_broken";
            } else {
                outcome = "transport_error";
            }
            AttemptRecord previous = currentIndex < attempts.size()
                    ? attempts.get(currentIndex) : null;
            AttemptRecord attempt = new AttemptRecord();
            attempt.outcome = outcome;
            attempt.errorClass = errorClass;
            attempt.httpStatus = responseOpened && previous != null ? previous.httpStatus : null;
            attempt.errorSource = previous == null ? null : previous.errorSource;
            attempt.shouldRetry = previous == null ? "absent" : previous.shouldRetry;
            attempt.retryAfterMs = previous == null ? null : previous.retryAfterMs;
            attempt.ttfbMs = responseOpened && previous != null ? previous.ttfbMs : null;
            attempt.requestId = previous == null ? null : previous.requestId;
            attempt.elapsedMs = elapsedSinceAttemptStart();
            store(attempt, phase);
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /** Records that the candidate index advanced after the last attempt. */
    public void onMoved() {
        try {
            if (attempts.isEmpty()) {
                return;
            }
            attempts.get(attempts.size() - 1).moved = true;
            failoverUsed = true;
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /** Records the first SSE event (or body byte) of a stream: TTFT, once. */
    public void onFirstEvent() {
        try {
            if (ttftMs == null && started) {
                ttftMs = Long.valueOf(Telemetry.clampDurationMs(
                        (clock.nanos() - firstStartedNanos) / 1_000_000L));
            }
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /**
     * Records a caller abort (cancellation, interruption, or closing a
     * stream before its end): the current attempt becomes {@code aborted},
     * keeping whatever status and ids it had already observed.
     */
    public void onAborted() {
        try {
            if (currentIndex < 0 || currentHost == null) {
                return;
            }
            AttemptRecord previous = currentIndex < attempts.size()
                    ? attempts.get(currentIndex) : null;
            AttemptRecord attempt = previous == null ? new AttemptRecord() : previous.copy();
            attempt.outcome = "aborted";
            attempt.elapsedMs = elapsedSinceAttemptStart();
            store(attempt, previous == null ? "none" : phases.get(currentIndex));
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /**
     * Marks that the engine gave up with retry budget spent (&sect;5.3
     * {@code exhausted}): set by the engine when a replayable attempt beyond
     * the first ended retryable but no retries remained.
     */
    public void markExhausted(boolean value) {
        exhausted = value;
    }

    /**
     * Finishes the logical call exactly once: derives the event and the
     * exact counter increments (one request-level row plus one attempt-level
     * row per attempt) and hands them to the sink. Idempotent; never throws.
     * Emits nothing for a header-only recorder, an unrecordable method, or a
     * call that never began an attempt.
     */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            emit();
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    public boolean isFinished() {
        return finished;
    }

    /** The attempts recorded so far; internal mutable state, used by tests. */
    public List<AttemptRecord> attempts() {
        return attempts;
    }

    public boolean isFailoverUsed() {
        return failoverUsed;
    }

    /** Milliseconds from the first attempt to the first stream event, or null. */
    public Long ttftMs() {
        return ttftMs;
    }

    private void emit() {
        if (sink == null || !recordable || attempts.isEmpty() || !started) {
            return;
        }
        AttemptRecord last = attempts.get(attempts.size() - 1);
        String finalOutcome = exhausted && attempts.size() > 1 && !"ok".equals(last.outcome)
                ? "exhausted" : last.outcome;
        String timeoutPhase = phases.get(phases.size() - 1);
        Long configuredTimeoutMs = timeouts.forPhase(timeoutPhase);
        long totalMs = Telemetry.clampDurationMs(
                (clock.nanos() - firstStartedNanos) / 1_000_000L);
        List<AttemptRecord> copies = new ArrayList<AttemptRecord>(attempts.size());
        for (AttemptRecord attempt : attempts) {
            copies.add(attempt.copy());
        }
        Event event = new Event(endpoint, method, streaming, providerPinned, model, copies,
                finalOutcome, last.httpStatus, totalMs, ttftMs, failoverUsed, timeoutPhase,
                configuredTimeoutMs);

        // Counter outcome is the final ATTEMPT's outcome, never "exhausted"
        // (schema module wins over §5.4's FinalOutcome typing).
        String counterOutcome = "exhausted".equals(finalOutcome) ? last.outcome : finalOutcome;
        String firstErrorClass = null;
        for (AttemptRecord attempt : attempts) {
            if (attempt.errorClass != null) {
                firstErrorClass = attempt.errorClass;
                break;
            }
        }
        CounterKey requestKey = new CounterKey(
                "request", endpoint, streaming, last.host, counterOutcome, firstErrorClass,
                Telemetry.statusClass(last.httpStatus), timeoutPhase,
                Telemetry.timeoutFloorMet(timeoutPhase, configuredTimeoutMs), providerPinned);
        CounterIncrement requestIncrement = new CounterIncrement(
                1L, attempts.size(), failoverUsed ? 1L : 0L,
                "ok".equals(attempts.get(0).outcome) ? 1L : 0L);
        requestIncrement.bucket(requestIncrement.totalMsHist, Telemetry.latencyBucket(totalMs));
        Long firstEventMs = ttftMs != null ? ttftMs : last.ttfbMs;
        if (firstEventMs != null) {
            requestIncrement.bucket(requestIncrement.firstEventMsHist,
                    Telemetry.latencyBucket(firstEventMs.longValue()));
        }
        List<CounterUpdate> counters = new ArrayList<CounterUpdate>(attempts.size() + 1);
        counters.add(new CounterUpdate(requestKey, requestIncrement));
        for (int index = 0; index < attempts.size(); index++) {
            AttemptRecord attempt = attempts.get(index);
            String phase = phases.get(index);
            Long attemptTimeoutMs = timeouts.forPhase(phase);
            CounterKey attemptKey = new CounterKey(
                    "attempt", endpoint, streaming, attempt.host, attempt.outcome,
                    attempt.errorClass, Telemetry.statusClass(attempt.httpStatus), phase,
                    Telemetry.timeoutFloorMet(phase, attemptTimeoutMs), providerPinned);
            counters.add(new CounterUpdate(attemptKey,
                    new CounterIncrement(1L, 1L, attempt.moved ? 1L : 0L, 0L)));
        }
        sink.onRequest(event, counters);
    }

    private long elapsedSinceAttemptStart() {
        return Telemetry.clampDurationMs((clock.nanos() - attemptStartedNanos) / 1_000_000L);
    }

    private void store(AttemptRecord attempt, String phase) {
        attempt.index = currentIndex;
        attempt.host = currentHost;
        if (currentIndex < attempts.size()) {
            attempts.set(currentIndex, attempt);
            phases.set(currentIndex, phase);
        } else {
            attempts.add(attempt);
            phases.add(phase);
        }
    }
}
