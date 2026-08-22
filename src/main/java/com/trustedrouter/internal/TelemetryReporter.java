package com.trustedrouter.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.TrustedRouter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * The beacon channel (client telemetry contract v1, &sect;4/&sect;5/&sect;6.2):
 * a bounded, out-of-engine delivery sink that posts content-free batches of
 * sampled request events and exact per-minute counters to
 * {@code POST {control_base}/v1/client-events}. Internal class with no
 * compatibility guarantees; mirrors the Python SDK's {@code TelemetryReporter}.
 *
 * <p><b>Own HTTP client.</b> This SDK has no out-of-engine single-shot
 * precedent ({@code Transport.executeAbsolute} rides {@code executeUrls}), so
 * the sender is a deliberate bypass: {@link OkHttpSender} owns a private
 * {@link OkHttpClient} that is neither the engine's nor the caller's injected
 * client, makes exactly one {@code Call.execute()} per flush, never enters
 * {@code Transport.executeUrls}, never consults {@code RetryPolicy}, never
 * stamps {@code x-tr-client}, and follows no redirects. A flush is never
 * retried (&sect;4); failure only schedules backoff.
 *
 * <p><b>One background worker.</b> A single daemon thread, started lazily on
 * the first recorded call — never at construction — flushes every 30 s, or
 * early at 50 buffered events or 60 KB, and drains a backlog in successive
 * batches of at most 100 events and 200 counters. {@link #close(long)} makes
 * one final single-shot flush bounded by the timeout (2 s at process exit,
 * through a JVM shutdown hook installed with the first worker).
 *
 * <p><b>Bounded.</b> At most 1 000 buffered events (the oldest success is
 * dropped first, then the oldest failure, every drop counted into
 * {@code dropped_since_last}), at most 256 counter keys per minute window
 * (folded coarser, never dropped: {@code error_class} to {@code unknown},
 * then {@code endpoint} to {@code inference_other}, then merged into an
 * existing key), closed windows retained for 24 h under 512 KiB, oldest
 * first, and every batch trimmed until it serialises under 65 536 bytes.
 *
 * <p><b>Responses.</b> 202 removes the sent items, resets backoff, and
 * applies the server policy only where it reduces volume; 400/401/403/404/410
 * disable telemetry for the process and clear every buffer; 413 drops the
 * batch; anything else (429/503, transport failure) backs off exponentially
 * from 60 s to 10 min honouring {@code Retry-After} up to 600 s; an
 * {@code x-tr-telemetry: off} header disables regardless of status.
 *
 * <p>Telemetry never fails a request (&sect;2.2): every entry point swallows
 * its own failures, and nothing here runs on the caller's thread except
 * bounded bookkeeping under a lock.
 */
public final class TelemetryReporter implements TelemetrySink {
    /** Clock seam: monotonic nanoseconds for ages and schedules, wall millis for {@code sent_at_ms}. */
    public interface Clock {
        long monotonicNanos();

        long wallMillis();
    }

    /** What one single-shot POST observed. */
    public static final class SendResult {
        public final int status;
        public final String retryAfter;
        public final String telemetryHeader;
        public final String body;

        public SendResult(int status, String retryAfter, String telemetryHeader, String body) {
            this.status = status;
            this.retryAfter = retryAfter;
            this.telemetryHeader = telemetryHeader;
            this.body = body;
        }
    }

    /**
     * The single-shot transport for one batch. Exactly one attempt per
     * call; an {@link IOException} means the batch was not accepted and the
     * reporter backs off — it never retries.
     */
    public interface Sender {
        SendResult send(String url, Map<String, String> headers, byte[] body, Long timeoutMs)
                throws IOException;

        void close();
    }

    /**
     * The production sender: a private OkHttp client built here and nowhere
     * else, so the beacon can never ride the engine's client, the caller's
     * injected client, their interceptors, the retry loop, or the
     * {@code x-tr-client} stamp. Created lazily on the first flush.
     */
    static final class OkHttpSender implements Sender {
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
        private static final long MAX_RESPONSE_BYTES = 16_384L;

        private OkHttpClient client;

        private synchronized OkHttpClient client() {
            if (client == null) {
                OkHttpClient configured = new OkHttpClient.Builder()
                        .connectTimeout(Telemetry.SENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(Telemetry.SENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .writeTimeout(Telemetry.SENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .callTimeout(Telemetry.SENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();
                // OkHttp 5 can still repeat a 503 carrying Retry-After: 0
                // inside its private follow-up layer. Neutralise that (plus
                // 408/421) so one reporter send is exactly one physical POST.
                client = PhysicalAttemptGuard.install(configured);
            }
            return client;
        }

        @Override
        public SendResult send(String url, Map<String, String> headers, byte[] body, Long timeoutMs)
                throws IOException {
            OkHttpClient sender = client();
            if (timeoutMs != null) {
                sender = sender.newBuilder()
                        .callTimeout(Math.max(1L, timeoutMs.longValue()), TimeUnit.MILLISECONDS)
                        .build();
            }
            Request.Builder request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.Companion.create(body, JSON));
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
            Response response = sender.newCall(request.build()).execute();
            try {
                String text = null;
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    BufferedSource source = responseBody.source();
                    source.request(MAX_RESPONSE_BYTES);
                    long available = Math.min(MAX_RESPONSE_BYTES, source.getBuffer().size());
                    text = source.getBuffer().readUtf8(available);
                }
                return new SendResult(
                        response.code(),
                        response.header("Retry-After"),
                        response.header("x-tr-telemetry"),
                        text);
            } finally {
                response.close();
            }
        }

        @Override
        public synchronized void close() {
            OkHttpClient current = client;
            client = null;
            if (current != null) {
                current.dispatcher().executorService().shutdown();
                current.connectionPool().evictAll();
            }
        }
    }

    /** Builds a reporter; every seam has a production default. */
    public static final class Builder {
        private String controlBaseUrl = TrustedRouter.DEFAULT_CONTROL_BASE_URL;
        private Supplier<String> apiKey = new Supplier<String>() {
            @Override
            public String get() {
                return null;
            }
        };
        private String workspaceId;
        private JsonObject sdkIdentity;
        private double successSampleRate = Telemetry.DEFAULT_SUCCESS_SAMPLE_RATE;
        private long flushIntervalMs = Telemetry.FLUSH_INTERVAL_MS;
        private long retentionBytes = Telemetry.RETENTION_BYTES;
        private Sender sender;
        private Clock clock;
        private Random random;
        private Boolean debug;
        private boolean automaticWorker = true;

        private Builder() {}

        public Builder controlBaseUrl(String value) {
            this.controlBaseUrl = value;
            return this;
        }

        public Builder apiKey(Supplier<String> value) {
            this.apiKey = value;
            return this;
        }

        public Builder workspaceId(String value) {
            this.workspaceId = value;
            return this;
        }

        public Builder sdkIdentity(JsonObject value) {
            this.sdkIdentity = value;
            return this;
        }

        public Builder successSampleRate(double value) {
            this.successSampleRate = value;
            return this;
        }

        public Builder flushIntervalMs(long value) {
            this.flushIntervalMs = value;
            return this;
        }

        /** Test seam for the retained-window byte cap. */
        Builder retentionBytes(long value) {
            this.retentionBytes = value;
            return this;
        }

        public Builder sender(Sender value) {
            this.sender = value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /** The sampling source; tests inject a scripted one. */
        public Builder random(Random value) {
            this.random = value;
            return this;
        }

        /** Forces the stderr batch echo on or off instead of reading the environment. */
        public Builder debug(Boolean value) {
            this.debug = value;
            return this;
        }

        /** Test seam: production always leaves the automatic worker enabled. */
        Builder automaticWorker(boolean value) {
            this.automaticWorker = value;
            return this;
        }

        public TelemetryReporter build() {
            return new TelemetryReporter(this);
        }
    }

    /** A normalised, sampled event waiting in the buffer. */
    private static final class WireEvent {
        private final JsonObject json;
        private final String finalOutcome;
        private final long completedAtMs;
        private final int estimatedBytes;

        private WireEvent(JsonObject json, long completedAtMs) {
            this.json = json;
            this.finalOutcome = json.get("final_outcome").getAsString();
            this.completedAtMs = completedAtMs;
            this.estimatedBytes = Telemetry.WIRE_JSON.toJson(json)
                    .getBytes(StandardCharsets.UTF_8).length;
        }

        private JsonObject toJson(long nowMs) {
            JsonObject copy = json.deepCopy();
            copy.addProperty("age_ms", Telemetry.bounded(
                    nowMs - completedAtMs, 0L, Telemetry.MAX_AGE_MS));
            return copy;
        }
    }

    /** One closed minute of counters, retained until delivered or expired. */
    private static final class CounterWindow {
        private final long windowStartMs;
        private final LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement>
                rows;
        private long sizeBytes;

        private CounterWindow(
                long windowStartMs,
                LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> rows) {
            this.windowStartMs = windowStartMs;
            this.rows = rows;
        }
    }

    private static final class CounterRef {
        private final CounterWindow window;
        private final RequestRecorder.CounterKey key;

        private CounterRef(CounterWindow window, RequestRecorder.CounterKey key) {
            this.window = window;
            this.key = key;
        }
    }

    private static final class Selected {
        private final String json;
        private final List<WireEvent> events;
        private final List<CounterRef> counters;
        private final long dropped;

        private Selected(String json, List<WireEvent> events, List<CounterRef> counters,
                long dropped) {
            this.json = json;
            this.events = events;
            this.counters = counters;
            this.dropped = dropped;
        }
    }

    private static final class SampleDecision {
        private final String reason;
        private final double rate;

        private SampleDecision(String reason, double rate) {
            this.reason = reason;
            this.rate = rate;
        }
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }

        @Override
        public long wallMillis() {
            return System.currentTimeMillis();
        }
    };

    private static final long MINUTE_MS = 60_000L;
    private static final long COUNTER_ESTIMATE_BYTES = 400L;
    private static final SecureRandom IDS = new SecureRandom();

    /** Every reporter that ever started a worker, for the process-exit flush. */
    private static final Set<TelemetryReporter> REPORTERS =
            Collections.newSetFromMap(new WeakHashMap<TelemetryReporter, Boolean>());
    private static boolean shutdownHookInstalled;

    private final String controlBaseUrl;
    private final Supplier<String> apiKey;
    private final String workspaceId;
    private final JsonObject sdkIdentity;
    private final long retentionBytes;
    private final Sender sender;
    private final Clock clock;
    private final Random random;
    private final boolean debug;
    private final boolean automaticWorker;
    private final long originNanos;
    private final String instanceId;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final ReentrantLock flushLock = new ReentrantLock();
    private boolean wakeRequested;
    private ExecutorService worker;
    private volatile Thread workerThread;
    private volatile double successSampleRate;
    private volatile long flushIntervalMs;
    private final List<WireEvent> events = new ArrayList<WireEvent>();
    private long eventsSizeBytes;
    private Long currentWindowStartMs;
    private LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement>
            currentCounters = newRows();
    private final Deque<CounterWindow> closedWindows = new ArrayDeque<CounterWindow>();
    private long retainedWindowBytes;
    private long droppedSinceLast;
    private long seq;
    private long backoffMs = Telemetry.BACKOFF_MIN_MS;
    private long backoffUntilMs;
    private long pausedUntilMs;
    private long nextFlushAtMs;
    private boolean urgentFlush;
    private boolean disabled;
    private boolean closed;
    private boolean stop;

    public static Builder builder() {
        return new Builder();
    }

    private TelemetryReporter(Builder builder) {
        String base = builder.controlBaseUrl == null ? "" : builder.controlBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.controlBaseUrl = base;
        this.apiKey = builder.apiKey;
        this.workspaceId = builder.workspaceId;
        this.sdkIdentity = Telemetry.normaliseSdkIdentity(
                builder.sdkIdentity == null ? Telemetry.sdkIdentity() : builder.sdkIdentity);
        this.successSampleRate = sampleRate(builder.successSampleRate);
        this.flushIntervalMs = flushInterval(builder.flushIntervalMs);
        this.retentionBytes = builder.retentionBytes;
        this.sender = builder.sender == null ? new OkHttpSender() : builder.sender;
        this.clock = builder.clock == null ? SYSTEM_CLOCK : builder.clock;
        this.random = builder.random == null ? new SecureRandom() : builder.random;
        this.debug = builder.debug != null
                ? builder.debug.booleanValue() : debugFromEnvironment(environment());
        this.automaticWorker = builder.automaticWorker;
        this.originNanos = this.clock.monotonicNanos();
        this.instanceId = hex(8);
    }

    /** Whether {@code TRUSTEDROUTER_TELEMETRY_DEBUG=1} asks for the stderr batch echo (&sect;6.3). */
    public static boolean debugFromEnvironment(Map<String, String> environ) {
        String value = environ == null ? null : environ.get("TRUSTEDROUTER_TELEMETRY_DEBUG");
        return value != null && "1".equals(value.trim());
    }

    static double sampleRate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Telemetry.DEFAULT_SUCCESS_SAMPLE_RATE;
        }
        return Math.min(1.0d, Math.max(0.0d, value));
    }

    static long flushInterval(long value) {
        if (value <= 0L) {
            return Telemetry.FLUSH_INTERVAL_MS;
        }
        return Math.min(Telemetry.BACKOFF_MAX_MS, value);
    }

    // ---- recording (caller thread, bounded bookkeeping only) ----------------

    @Override
    public void onRequest(
            RequestRecorder.Event event, List<RequestRecorder.CounterUpdate> counters) {
        try {
            long now = nowMs();
            SampleDecision reason = sampleReason(event);
            WireEvent sampled = null;
            boolean invalidSample = false;
            if (reason != null) {
                JsonObject wire = wireEvent(event, reason);
                if (wire == null) {
                    invalidSample = true;
                } else {
                    sampled = new WireEvent(wire, now);
                }
            }
            lock.lock();
            try {
                if (disabled || closed) {
                    return;
                }
                rollWindowLocked(now);
                mergeCountersLocked(counters);
                if (invalidSample) {
                    droppedSinceLast++;
                }
                if (sampled != null) {
                    appendEventLocked(sampled);
                }
                startWorkerLocked(now);
                if (events.size() >= Telemetry.BATCH_TRIGGER_EVENTS
                        || eventsSizeBytes + retainedWindowBytes
                                + currentCounters.size() * COUNTER_ESTIMATE_BYTES
                                >= Telemetry.BATCH_TRIGGER_BYTES) {
                    urgentFlush = true;
                    wakeLocked();
                }
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    private SampleDecision sampleReason(RequestRecorder.Event event) {
        if (!"ok".equals(event.finalOutcome)) {
            return new SampleDecision("failure", 1.0d);
        }
        if (event.attempts.size() > 1 || event.failoverUsed) {
            return new SampleDecision("retried", 1.0d);
        }
        if (Telemetry.bounded(event.totalMs, 0L, Telemetry.MAX_DURATION_MS)
                > Telemetry.SLOW_REQUEST_MS) {
            return new SampleDecision("slow", 1.0d);
        }
        double rate = successSampleRate;
        double draw = random.nextDouble();
        if (rate <= 0.0d || draw >= rate) {
            return null;
        }
        return new SampleDecision("random", rate);
    }

    /**
     * The wire form of one event (&sect;5.3), every field bounded exactly as
     * the Python SDK's {@code _wire_event}/{@code _wire_attempt}: clamps,
     * closed vocabularies, anchored regexes, {@code should_retry} absent
     * rather than null when unobserved. Returns null when the event cannot
     * be represented (no attempts, bad sample fields) — counted as a drop.
     */
    static JsonObject wireEvent(RequestRecorder.Event event, SampleDecision decision) {
        if (event == null || event.attempts.isEmpty()) {
            return null;
        }
        JsonArray attempts = new JsonArray();
        int limit = Math.min(16, event.attempts.size());
        for (int index = 0; index < limit; index++) {
            attempts.add(wireAttempt(event.attempts.get(index)));
        }
        if (decision == null || !Telemetry.SAMPLE_REASONS.contains(decision.reason)
                || Double.isNaN(decision.rate) || decision.rate <= 0.0d
                || decision.rate > 1.0d) {
            return null;
        }
        JsonObject wire = new JsonObject();
        wire.addProperty("age_ms", 0L);
        wire.addProperty("plane", "inference");
        wire.addProperty("endpoint", Telemetry.ENDPOINTS.contains(event.endpoint)
                ? event.endpoint : "inference_other");
        wire.addProperty("method", Telemetry.BEACON_METHODS.contains(event.method)
                ? event.method : "POST");
        wire.addProperty("streaming", event.streaming);
        wire.addProperty("provider_pinned", event.providerPinned);
        wire.addProperty("model", event.model != null
                && Telemetry.MODEL.matcher(event.model).matches() ? event.model : null);
        wire.add("attempts", attempts);
        String finalOutcome = Telemetry.FINAL_OUTCOMES.contains(event.finalOutcome)
                ? event.finalOutcome
                : attempts.get(attempts.size() - 1).getAsJsonObject()
                        .get("outcome").getAsString();
        wire.addProperty("final_outcome", finalOutcome);
        wire.addProperty("final_http_status", Telemetry.boundedOrNull(
                event.finalHttpStatus == null ? null : Long.valueOf(event.finalHttpStatus),
                100L, 599L));
        wire.addProperty("total_ms", Telemetry.bounded(
                event.totalMs, 0L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("ttft_ms", Telemetry.boundedOrNull(
                event.ttftMs, 0L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("failover_used", event.failoverUsed);
        wire.addProperty("timeout_phase", Telemetry.TIMEOUT_PHASES.contains(event.timeoutPhase)
                ? event.timeoutPhase : "none");
        wire.addProperty("configured_timeout_ms", Telemetry.boundedOrNull(
                event.configuredTimeoutMs, 1L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("sample_rate", decision.rate);
        wire.addProperty("sample_reason", decision.reason);
        return wire;
    }

    static JsonObject wireAttempt(RequestRecorder.AttemptRecord attempt) {
        JsonObject wire = new JsonObject();
        wire.addProperty("index", Telemetry.bounded(attempt.index, 0L, 99L));
        wire.addProperty("host", Telemetry.HOSTS.contains(attempt.host) ? attempt.host : "custom");
        wire.addProperty("outcome", Telemetry.OUTCOMES.contains(attempt.outcome)
                ? attempt.outcome : "transport_error");
        wire.addProperty("http_status", Telemetry.boundedOrNull(
                attempt.httpStatus == null ? null : Long.valueOf(attempt.httpStatus),
                100L, 599L));
        wire.addProperty("error_class", Telemetry.ERROR_CLASSES.contains(attempt.errorClass)
                ? attempt.errorClass : null);
        wire.addProperty("error_source", Telemetry.ERROR_SOURCES.contains(attempt.errorSource)
                ? attempt.errorSource : null);
        wire.addProperty("retry_after_ms", Telemetry.boundedOrNull(
                attempt.retryAfterMs, 0L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("elapsed_ms", Telemetry.bounded(
                attempt.elapsedMs, 0L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("ttfb_ms", Telemetry.boundedOrNull(
                attempt.ttfbMs, 0L, Telemetry.MAX_DURATION_MS));
        wire.addProperty("request_id", attempt.requestId != null
                && Telemetry.REQUEST_ID.matcher(attempt.requestId).matches()
                ? attempt.requestId : null);
        wire.addProperty("moved", attempt.moved);
        if ("true".equals(attempt.shouldRetry)) {
            wire.addProperty("should_retry", true);
        } else if ("false".equals(attempt.shouldRetry)) {
            wire.addProperty("should_retry", false);
        }
        return wire;
    }

    private void appendEventLocked(WireEvent event) {
        if (events.size() >= Telemetry.MAX_EVENTS) {
            dropBufferedEventLocked();
        }
        events.add(event);
        eventsSizeBytes += event.estimatedBytes;
    }

    /** Drops the oldest success, or the oldest event when none is a success; counted. */
    private void dropBufferedEventLocked() {
        int index = 0;
        for (int candidate = 0; candidate < events.size(); candidate++) {
            if ("ok".equals(events.get(candidate).finalOutcome)) {
                index = candidate;
                break;
            }
        }
        WireEvent dropped = events.remove(index);
        eventsSizeBytes -= dropped.estimatedBytes;
        droppedSinceLast++;
    }

    // ---- counters ---------------------------------------------------------

    private static LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement>
            newRows() {
        return new LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement>();
    }

    private static long minuteStart(long nowMs) {
        return (Math.max(0L, nowMs) / MINUTE_MS) * MINUTE_MS;
    }

    private void rollWindowLocked(long now) {
        long start = minuteStart(now);
        if (currentWindowStartMs == null) {
            currentWindowStartMs = Long.valueOf(start);
            return;
        }
        if (start > currentWindowStartMs.longValue()) {
            closeCurrentWindowLocked(now);
            currentWindowStartMs = Long.valueOf(start);
        }
    }

    private void closeCurrentWindowLocked(long now) {
        if (currentCounters.isEmpty() || currentWindowStartMs == null) {
            return;
        }
        CounterWindow window = new CounterWindow(currentWindowStartMs.longValue(), currentCounters);
        window.sizeBytes = windowSize(window);
        closedWindows.addLast(window);
        retainedWindowBytes += window.sizeBytes;
        currentCounters = newRows();
        currentWindowStartMs = Long.valueOf(minuteStart(now));
        pruneWindowsLocked(now);
    }

    private static long windowSize(CounterWindow window) {
        JsonArray rows = new JsonArray();
        for (Map.Entry<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> entry
                : window.rows.entrySet()) {
            rows.add(counterRow(entry.getKey(), entry.getValue(), 0L));
        }
        return Telemetry.WIRE_JSON.toJson(rows).getBytes(StandardCharsets.UTF_8).length;
    }

    private void dropWindowLocked(CounterWindow window) {
        retainedWindowBytes -= window.sizeBytes;
        droppedSinceLast += window.rows.size();
    }

    private void pruneWindowsLocked(long now) {
        while (!closedWindows.isEmpty()
                && now - closedWindows.peekFirst().windowStartMs > Telemetry.RETENTION_MS) {
            dropWindowLocked(closedWindows.pollFirst());
        }
        while (!closedWindows.isEmpty() && retainedWindowBytes > retentionBytes) {
            dropWindowLocked(closedWindows.pollFirst());
        }
    }

    /** Re-validates a key against the vocabulary (Python {@code _normalise_counter_key}). */
    static RequestRecorder.CounterKey normaliseCounterKey(RequestRecorder.CounterKey key) {
        if (key == null || !Telemetry.LEVELS.contains(key.level)
                || !Telemetry.FINAL_OUTCOMES.contains(key.outcome)) {
            return null;
        }
        String errorClass = key.errorClass;
        if (errorClass != null && !Telemetry.ERROR_CLASSES.contains(errorClass)) {
            errorClass = "unknown";
        }
        return new RequestRecorder.CounterKey(
                key.level,
                Telemetry.ENDPOINTS.contains(key.endpoint) ? key.endpoint : "inference_other",
                key.streaming,
                Telemetry.HOSTS.contains(key.host) ? key.host : "custom",
                key.outcome,
                errorClass,
                Telemetry.HTTP_STATUS_CLASSES.contains(key.httpStatusClass)
                        ? key.httpStatusClass : "none",
                Telemetry.TIMEOUT_PHASES.contains(key.timeoutPhase) ? key.timeoutPhase : "none",
                key.timeoutFloorMet,
                key.providerPinned);
    }

    static void mergeIncrement(
            RequestRecorder.CounterIncrement target, RequestRecorder.CounterIncrement increment) {
        target.requests += Telemetry.bounded(increment.requests, 0L, Telemetry.MAX_COUNT);
        target.attempts += Telemetry.bounded(increment.attempts, 0L, Telemetry.MAX_COUNT);
        target.failoverUsed += Telemetry.bounded(increment.failoverUsed, 0L, Telemetry.MAX_COUNT);
        target.firstAttemptSuccess += Telemetry.bounded(
                increment.firstAttemptSuccess, 0L, Telemetry.MAX_COUNT);
        mergeHistogram(target.totalMsHist, increment.totalMsHist);
        mergeHistogram(target.firstEventMsHist, increment.firstEventMsHist);
    }

    private static void mergeHistogram(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            if (!Telemetry.LATENCY_BUCKETS.contains(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            long count = Telemetry.bounded(entry.getValue().longValue(), 0L, Telemetry.MAX_COUNT);
            Long current = target.get(entry.getKey());
            target.put(entry.getKey(),
                    Long.valueOf((current == null ? 0L : current.longValue()) + count));
        }
    }

    private static RequestRecorder.CounterKey folded(
            RequestRecorder.CounterKey key, boolean endpoint) {
        RequestRecorder.CounterKey result = key.withErrorClass("unknown");
        return endpoint ? result.withEndpoint("inference_other") : result;
    }

    /**
     * The key a new increment lands on under the 256-key cap — the exact
     * Python fold ladder: the key itself while there is room; its
     * error-class fold if present; else the first existing key that matches
     * it except for error class, re-keyed (and merged) to its own fold; else
     * its endpoint fold if present; else the first existing key that matches
     * except for endpoint and error class, re-keyed to its own fold; else
     * any existing key. Counts stay exact; only the key gets coarser.
     */
    private RequestRecorder.CounterKey counterTargetLocked(RequestRecorder.CounterKey key) {
        if (currentCounters.containsKey(key)
                || currentCounters.size() < Telemetry.MAX_WINDOW_KEYS) {
            return key;
        }
        RequestRecorder.CounterKey errorFolded = folded(key, false);
        if (currentCounters.containsKey(errorFolded)) {
            return errorFolded;
        }
        RequestRecorder.CounterKey errorCompatible = null;
        for (RequestRecorder.CounterKey existing : currentCounters.keySet()) {
            if (existing.matchesExceptErrorClass(key)) {
                errorCompatible = existing;
                break;
            }
        }
        if (errorCompatible != null) {
            return refoldLocked(errorCompatible, false);
        }
        RequestRecorder.CounterKey endpointFolded = folded(key, true);
        if (currentCounters.containsKey(endpointFolded)) {
            return endpointFolded;
        }
        RequestRecorder.CounterKey compatible = null;
        for (RequestRecorder.CounterKey existing : currentCounters.keySet()) {
            if (existing.matchesExceptEndpointAndErrorClass(key)) {
                compatible = existing;
                break;
            }
        }
        if (compatible != null) {
            return refoldLocked(compatible, true);
        }
        return currentCounters.keySet().iterator().next();
    }

    private RequestRecorder.CounterKey refoldLocked(
            RequestRecorder.CounterKey existing, boolean endpoint) {
        RequestRecorder.CounterIncrement previous = currentCounters.remove(existing);
        RequestRecorder.CounterKey target = folded(existing, endpoint);
        RequestRecorder.CounterIncrement merged = new RequestRecorder.CounterIncrement();
        mergeIncrement(merged, previous);
        currentCounters.put(target, merged);
        return target;
    }

    private void mergeCountersLocked(List<RequestRecorder.CounterUpdate> counters) {
        if (counters == null) {
            return;
        }
        for (RequestRecorder.CounterUpdate update : counters) {
            if (update == null || update.key == null || update.increment == null) {
                droppedSinceLast++;
                continue;
            }
            RequestRecorder.CounterKey key = normaliseCounterKey(update.key);
            if (key == null) {
                droppedSinceLast++;
                continue;
            }
            RequestRecorder.CounterKey targetKey = counterTargetLocked(key);
            RequestRecorder.CounterIncrement target = currentCounters.get(targetKey);
            if (target == null) {
                target = new RequestRecorder.CounterIncrement();
                currentCounters.put(targetKey, target);
            }
            mergeIncrement(target, update.increment);
        }
    }

    static JsonObject counterRow(
            RequestRecorder.CounterKey key, RequestRecorder.CounterIncrement increment,
            long windowAgeMs) {
        JsonObject row = new JsonObject();
        row.addProperty("window_start_age_ms", Telemetry.bounded(
                windowAgeMs, 0L, Telemetry.MAX_AGE_MS));
        row.addProperty("level", key.level);
        row.addProperty("endpoint", key.endpoint);
        row.addProperty("streaming", key.streaming);
        row.addProperty("host", key.host);
        row.addProperty("outcome", key.outcome);
        row.addProperty("error_class", key.errorClass);
        row.addProperty("http_status_class", key.httpStatusClass);
        row.addProperty("timeout_phase", key.timeoutPhase);
        row.addProperty("timeout_floor_met", key.timeoutFloorMet);
        row.addProperty("provider_pinned", key.providerPinned);
        row.addProperty("requests", Telemetry.bounded(increment.requests, 1L, Telemetry.MAX_COUNT));
        row.addProperty("attempts", Telemetry.bounded(increment.attempts, 0L, Telemetry.MAX_COUNT));
        row.addProperty("failover_used", Telemetry.bounded(
                increment.failoverUsed, 0L, Telemetry.MAX_COUNT));
        row.addProperty("first_attempt_success", Telemetry.bounded(
                increment.firstAttemptSuccess, 0L, Telemetry.MAX_COUNT));
        row.add("total_ms_hist", histogram(increment.totalMsHist));
        row.add("first_event_ms_hist", histogram(increment.firstEventMsHist));
        return row;
    }

    private static JsonObject histogram(Map<String, Long> values) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            object.addProperty(entry.getKey(), entry.getValue());
        }
        return object;
    }

    // ---- worker -----------------------------------------------------------

    private void startWorkerLocked(long now) {
        if (!automaticWorker || worker != null || disabled || closed) {
            return;
        }
        nextFlushAtMs = now + flushIntervalMs;
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "trustedrouter-telemetry");
                thread.setDaemon(true);
                return thread;
            }
        });
        worker.execute(new Runnable() {
            @Override
            public void run() {
                workerThread = Thread.currentThread();
                runWorker();
            }
        });
        register(this);
    }

    private void runWorker() {
        try {
            while (true) {
                long now = nowMs();
                lock.lock();
                try {
                    if (stop) {
                        return;
                    }
                    long deadline = Math.max(nextFlushAtMs, Math.max(pausedUntilMs, backoffUntilMs));
                    boolean urgent = urgentFlush && now >= Math.max(pausedUntilMs, backoffUntilMs);
                    if (urgent) {
                        urgentFlush = false;
                    }
                    if (!urgent && now < deadline) {
                        if (!wakeRequested) {
                            wake.await(deadline - now, TimeUnit.MILLISECONDS);
                        }
                        wakeRequested = false;
                        continue;
                    }
                } finally {
                    lock.unlock();
                }
                flushOnce(null);
                lock.lock();
                try {
                    nextFlushAtMs = nowMs() + flushIntervalMs;
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException impossible) {
            // The worker is best-effort; a failure here ends delivery, never a request.
        }
    }

    private void wakeLocked() {
        wakeRequested = true;
        wake.signalAll();
    }

    // ---- flushing ---------------------------------------------------------

    /** Synchronously attempts one flush; intended for deterministic tests. Never throws. */
    public boolean flushNow() {
        try {
            return flushOnce(null);
        } catch (RuntimeException impossible) {
            return false;
        }
    }

    private boolean flushOnce(Long timeoutMs) {
        flushLock.lock();
        try {
            long now = nowMs();
            lock.lock();
            try {
                if (disabled || now < Math.max(pausedUntilMs, backoffUntilMs)) {
                    return false;
                }
            } finally {
                lock.unlock();
            }
            String key = apiKey();
            if (key == null) {
                return false;
            }
            Selected selected;
            lock.lock();
            try {
                selected = selectBatchLocked(now);
            } finally {
                lock.unlock();
            }
            if (selected == null) {
                return false;
            }
            if (debug) {
                System.err.println("trustedrouter telemetry batch: " + selected.json);
                System.err.flush();
            }
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + key);
            headers.put("User-Agent", RequestFactory.userAgent());
            headers.put("Content-Type", "application/json");
            if (workspaceId != null && !workspaceId.isEmpty()) {
                headers.put("X-TrustedRouter-Workspace", workspaceId);
            }
            SendResult result;
            try {
                result = sender.send(
                        controlBaseUrl + Telemetry.CLIENT_EVENTS_PATH, headers,
                        selected.json.getBytes(StandardCharsets.UTF_8), timeoutMs);
            } catch (IOException | RuntimeException failure) {
                lock.lock();
                try {
                    setBackoffLocked(nowMs(), null);
                } finally {
                    lock.unlock();
                }
                return false;
            }
            handleResponse(result, nowMs(), selected);
            return result.status == 202;
        } finally {
            flushLock.unlock();
        }
    }

    private String apiKey() {
        try {
            String value = apiKey == null ? null : apiKey.get();
            return value == null || value.isEmpty() ? null : value;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Builds the next batch (&sect;5.1): up to 100 buffered events and 200
     * retained counter rows (oldest windows first, with their ages), then
     * trimmed from the end until it serialises under 65 536 bytes.
     */
    private Selected selectBatchLocked(long now) {
        rollWindowLocked(now);
        closeCurrentWindowLocked(now);
        pruneWindowsLocked(now);
        List<WireEvent> eventRefs = new ArrayList<WireEvent>();
        JsonArray wireEvents = new JsonArray();
        for (WireEvent buffered : events) {
            eventRefs.add(buffered);
            wireEvents.add(buffered.toJson(now));
            if (wireEvents.size() >= Telemetry.MAX_BATCH_EVENTS) {
                break;
            }
        }
        List<CounterRef> counterRefs = new ArrayList<CounterRef>();
        JsonArray wireCounters = new JsonArray();
        outer:
        for (CounterWindow window : closedWindows) {
            long ageMs = now - window.windowStartMs;
            for (Map.Entry<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> entry
                    : window.rows.entrySet()) {
                counterRefs.add(new CounterRef(window, entry.getKey()));
                wireCounters.add(counterRow(entry.getKey(), entry.getValue(), ageMs));
                if (wireCounters.size() >= Telemetry.MAX_BATCH_COUNTERS) {
                    break outer;
                }
            }
        }
        if (wireEvents.size() == 0 && wireCounters.size() == 0) {
            return null;
        }
        long dropped = droppedSinceLast;
        JsonObject batch = new JsonObject();
        batch.addProperty("schema_version", Telemetry.SCHEMA_VERSION);
        batch.addProperty("batch_id", hex(16));
        batch.addProperty("instance_id", instanceId);
        batch.addProperty("seq", seq);
        batch.addProperty("sent_at_ms", clock.wallMillis());
        batch.add("sdk", sdkIdentity.deepCopy());
        batch.addProperty("synthetic", false);
        batch.addProperty("dropped_since_last", dropped);
        batch.add("events", wireEvents);
        batch.add("counters", wireCounters);
        seq++;
        String json = Telemetry.WIRE_JSON.toJson(batch);
        while (json.getBytes(StandardCharsets.UTF_8).length > Telemetry.MAX_BATCH_BYTES) {
            if (wireEvents.size() > 0) {
                wireEvents.remove(wireEvents.size() - 1);
                eventRefs.remove(eventRefs.size() - 1);
            } else if (wireCounters.size() > 0) {
                wireCounters.remove(wireCounters.size() - 1);
                counterRefs.remove(counterRefs.size() - 1);
            } else {
                return null;
            }
            json = Telemetry.WIRE_JSON.toJson(batch);
        }
        return new Selected(json, eventRefs, counterRefs, dropped);
    }

    private void handleResponse(SendResult result, long now, Selected selected) {
        lock.lock();
        try {
            String telemetryHeader = result.telemetryHeader == null
                    ? "" : result.telemetryHeader.trim().toLowerCase(Locale.ROOT);
            if ("off".equals(telemetryHeader)) {
                disableLocked();
                return;
            }
            if (result.status == 202) {
                removeSelectedLocked(selected);
                droppedSinceLast = Math.max(0L, droppedSinceLast - selected.dropped);
                backoffMs = Telemetry.BACKOFF_MIN_MS;
                backoffUntilMs = 0L;
                applyPolicyLocked(result.body, now);
                return;
            }
            if (result.status == 400 || result.status == 401 || result.status == 403
                    || result.status == 404 || result.status == 410) {
                disableLocked();
                return;
            }
            if (result.status == 413) {
                removeSelectedLocked(selected);
                droppedSinceLast += selected.events.size() + selected.counters.size();
                return;
            }
            setBackoffLocked(now, retryAfterMs(result.retryAfter));
        } finally {
            lock.unlock();
        }
    }

    /** {@code Retry-After} in milliseconds when it is a finite 0..600 s value, else null. */
    static Long retryAfterMs(String header) {
        if (header == null) {
            return null;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(header.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0.0d
                || seconds * 1000.0d > Telemetry.MAX_RETRY_AFTER_MS) {
            return null;
        }
        return Long.valueOf((long) (seconds * 1000.0d));
    }

    private void setBackoffLocked(long now, Long retryAfter) {
        long delay = backoffMs;
        if (retryAfter != null) {
            delay = Math.max(delay, retryAfter.longValue());
        }
        backoffUntilMs = now + Math.min(Telemetry.BACKOFF_MAX_MS, delay);
        backoffMs = Math.min(Telemetry.BACKOFF_MAX_MS,
                Math.max(Telemetry.BACKOFF_MIN_MS, backoffMs * 2L));
        wakeLocked();
    }

    private void removeSelectedLocked(Selected selected) {
        Set<WireEvent> sent = Collections.newSetFromMap(new IdentityHashMap<WireEvent, Boolean>());
        sent.addAll(selected.events);
        Iterator<WireEvent> buffered = events.iterator();
        while (buffered.hasNext()) {
            if (sent.contains(buffered.next())) {
                buffered.remove();
            }
        }
        long size = 0L;
        for (WireEvent event : events) {
            size += event.estimatedBytes;
        }
        eventsSizeBytes = size;
        Set<CounterWindow> changed =
                Collections.newSetFromMap(new IdentityHashMap<CounterWindow, Boolean>());
        for (CounterRef ref : selected.counters) {
            if (ref.window.rows.remove(ref.key) != null) {
                changed.add(ref.window);
            }
        }
        for (CounterWindow window : changed) {
            retainedWindowBytes -= window.sizeBytes;
            window.sizeBytes = window.rows.isEmpty() ? 0L : windowSize(window);
            retainedWindowBytes += window.sizeBytes;
        }
        Iterator<CounterWindow> windows = closedWindows.iterator();
        while (windows.hasNext()) {
            if (windows.next().rows.isEmpty()) {
                windows.remove();
            }
        }
    }

    /** Applies a 202 {@code policy} only where it reduces volume (&sect;4). */
    private void applyPolicyLocked(String body, long now) {
        JsonElement payload = JsonSupport.parseOrNull(body);
        if (payload == null || !payload.isJsonObject()) {
            return;
        }
        JsonElement policyElement = payload.getAsJsonObject().get("policy");
        if (policyElement == null || !policyElement.isJsonObject()) {
            return;
        }
        JsonObject policy = policyElement.getAsJsonObject();
        if (policy.has("success_sample_rate")) {
            Double rate = Telemetry.finiteDouble(policy.get("success_sample_rate"));
            if (rate != null && rate.doubleValue() >= 0.0d
                    && rate.doubleValue() < successSampleRate) {
                successSampleRate = rate.doubleValue();
            }
        }
        if (policy.has("flush_seconds")) {
            Double flushSeconds = Telemetry.finiteDouble(policy.get("flush_seconds"));
            if (flushSeconds != null
                    && flushSeconds.doubleValue() * 1000.0d > (double) flushIntervalMs) {
                flushIntervalMs = Math.min(Telemetry.BACKOFF_MAX_MS,
                        (long) (flushSeconds.doubleValue() * 1000.0d));
            }
        }
        Double pauseSeconds = Telemetry.finiteDouble(policy.get("pause_seconds"));
        if (pauseSeconds != null && pauseSeconds.doubleValue() >= 0.0d
                && pauseSeconds.doubleValue() * 1000.0d <= (double) Telemetry.MAX_PAUSE_MS) {
            pausedUntilMs = Math.max(pausedUntilMs,
                    now + (long) (pauseSeconds.doubleValue() * 1000.0d));
        }
    }

    private void disableLocked() {
        disabled = true;
        events.clear();
        eventsSizeBytes = 0L;
        currentCounters.clear();
        closedWindows.clear();
        retainedWindowBytes = 0L;
        droppedSinceLast = 0L;
        stop = true;
        wakeLocked();
    }

    // ---- lifecycle --------------------------------------------------------

    /**
     * Stops recording, makes one final single-shot flush bounded by
     * {@code timeoutMs} (the process-exit bound is 2 s), and stops the
     * worker. Idempotent; never throws; never blocks longer than the bound.
     */
    public void close(long timeoutMs) {
        long timeout = Math.max(0L, timeoutMs);
        long startedNanos = System.nanoTime();
        ExecutorService activeWorker;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            activeWorker = worker;
            stop = true;
            wakeLocked();
        } finally {
            lock.unlock();
        }
        final long finalTimeout = timeout;
        Thread finalFlush = new Thread(new Runnable() {
            @Override
            public void run() {
                finalFlush(finalTimeout);
            }
        }, "trustedrouter-telemetry-close");
        finalFlush.setDaemon(true);
        finalFlush.start();
        try {
            finalFlush.join(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        long remaining = Math.max(0L, timeout - elapsedMs);
        if (activeWorker != null) {
            activeWorker.shutdownNow();
            try {
                activeWorker.awaitTermination(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void finalFlush(long timeoutMs) {
        try {
            flushOnce(Long.valueOf(timeoutMs));
        } catch (RuntimeException impossible) {
            // Best effort at exit.
        } finally {
            try {
                sender.close();
            } catch (RuntimeException impossible) {
                // Best effort at exit.
            }
        }
    }

    private static void register(TelemetryReporter reporter) {
        synchronized (REPORTERS) {
            REPORTERS.add(reporter);
            if (!shutdownHookInstalled) {
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        @Override
                        public void run() {
                            closeAll();
                        }
                    }, "trustedrouter-telemetry-shutdown"));
                    shutdownHookInstalled = true;
                } catch (IllegalStateException | SecurityException denied) {
                    // Already shutting down, or hooks forbidden: close() remains available.
                }
            }
        }
    }

    /** The process-exit flush: every live reporter gets its bounded final flush. */
    static void closeAll() {
        List<TelemetryReporter> snapshot;
        synchronized (REPORTERS) {
            snapshot = new ArrayList<TelemetryReporter>(REPORTERS);
        }
        for (TelemetryReporter reporter : snapshot) {
            try {
                reporter.close(Telemetry.FINAL_FLUSH_MS);
            } catch (RuntimeException impossible) {
                // Best effort at exit.
            }
        }
    }

    // ---- observation (tests and diagnostics) --------------------------------

    public boolean isDisabled() {
        lock.lock();
        try {
            return disabled;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /** The worker thread once started on the first record, else null. */
    public Thread workerThread() {
        return workerThread;
    }

    public String instanceId() {
        return instanceId;
    }

    public double successSampleRate() {
        return successSampleRate;
    }

    public long flushIntervalMs() {
        return flushIntervalMs;
    }

    public long droppedSinceLast() {
        lock.lock();
        try {
            return droppedSinceLast;
        } finally {
            lock.unlock();
        }
    }

    public long retainedWindowBytes() {
        lock.lock();
        try {
            return retainedWindowBytes;
        } finally {
            lock.unlock();
        }
    }

    public long seq() {
        lock.lock();
        try {
            return seq;
        } finally {
            lock.unlock();
        }
    }

    /** Copies of the buffered wire events (age 0), oldest first. */
    public List<JsonObject> bufferedEvents() {
        lock.lock();
        try {
            List<JsonObject> copies = new ArrayList<JsonObject>(events.size());
            for (WireEvent event : events) {
                copies.add(event.json.deepCopy());
            }
            return copies;
        } finally {
            lock.unlock();
        }
    }

    /** A copy of the open minute window's rows, in insertion order. */
    public Map<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> currentCounters() {
        lock.lock();
        try {
            LinkedHashMap<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> copy =
                    newRows();
            for (Map.Entry<RequestRecorder.CounterKey, RequestRecorder.CounterIncrement> entry
                    : currentCounters.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        } finally {
            lock.unlock();
        }
    }

    /** The start offsets (reporter-monotonic ms) of the retained closed windows, oldest first. */
    public List<Long> closedWindowStartsMs() {
        lock.lock();
        try {
            List<Long> starts = new ArrayList<Long>();
            for (CounterWindow window : closedWindows) {
                starts.add(Long.valueOf(window.windowStartMs));
            }
            return starts;
        } finally {
            lock.unlock();
        }
    }

    /** The open window's start offset (reporter-monotonic ms), or null before the first record. */
    public Long currentWindowStartMs() {
        lock.lock();
        try {
            return currentWindowStartMs;
        } finally {
            lock.unlock();
        }
    }

    /** Test seam: applies retention pruning at the current clock. */
    void pruneWindowsNow() {
        lock.lock();
        try {
            pruneWindowsLocked(nowMs());
        } finally {
            lock.unlock();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private long nowMs() {
        return Math.max(0L, (clock.monotonicNanos() - originNanos) / 1_000_000L);
    }

    private static String hex(int bytes) {
        byte[] raw = new byte[bytes];
        IDS.nextBytes(raw);
        StringBuilder text = new StringBuilder(bytes * 2);
        for (byte value : raw) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16));
            text.append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }

    private static Map<String, String> environment() {
        try {
            return System.getenv();
        } catch (SecurityException denied) {
            return Collections.emptyMap();
        }
    }
}
