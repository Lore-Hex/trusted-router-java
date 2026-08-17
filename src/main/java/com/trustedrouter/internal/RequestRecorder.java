package com.trustedrouter.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records one logical inference call across the retry/failover loop and
 * derives the per-attempt {@code x-tr-client} header (contract v1, &sect;3.2).
 * Internal class with no compatibility guarantees; mirrors the Python SDK's
 * {@code RequestRecorder} header subset.
 *
 * <p>One recorder is created per logical call by the transport engine — for
 * INFERENCE-plane calls only, and only when telemetry resolved enabled — and
 * threaded through {@code Transport.executeUrls}, the single emit point
 * (&sect;6.1). Control-plane and absolute fetches never construct one, so
 * they produce no header and no recorder activity.
 *
 * <p>Telemetry never fails a request (&sect;2.2): every value below comes
 * from a closed internal vocabulary or clamped long arithmetic, and
 * {@link #headerValue()} re-validates the grammar and byte cap anyway,
 * sending NOTHING rather than ever throwing.
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
    }

    /** Monotonic clock seam so tests can pin durations; production uses nanoTime. */
    public interface NanoClock {
        long nanos();
    }

    private static final NanoClock SYSTEM_CLOCK = new NanoClock() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };

    private final boolean streaming;
    private final NanoClock clock;
    private final List<AttemptRecord> attempts = new ArrayList<AttemptRecord>();
    private boolean failoverUsed;
    private boolean started;
    private long firstStartedNanos;
    private long attemptStartedNanos;
    private String currentHost;
    private int currentIndex = -1;

    public RequestRecorder(boolean streaming) {
        this(streaming, SYSTEM_CLOCK);
    }

    public RequestRecorder(boolean streaming, NanoClock clock) {
        this.streaming = streaming;
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

    /** Records an attempt that produced an HTTP response. */
    public void onResponse(int statusCode) {
        try {
            if (currentIndex < 0 || currentHost == null) {
                return;
            }
            record(statusCode < 400 ? "ok" : "http_error", null);
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
    }

    /**
     * Records an attempt where the HTTP client threw. Must be called at the
     * engine's {@code IOException} catch, BEFORE the failure is flattened
     * into an {@code InternalException} message string (&sect;6.1). A
     * hostile exception subtype (throwing {@code getCause()} or
     * {@code getMessage()}) must not replace the engine's retry decision, so
     * the whole hook fails open.
     */
    public void onTransportError(IOException error) {
        try {
            if (currentIndex < 0 || currentHost == null) {
                return;
            }
            record(
                    Telemetry.isTimeout(error) ? "timeout" : "transport_error",
                    Telemetry.classifyTransportError(error));
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

    /** The attempts recorded so far; internal mutable state, used by tests. */
    public List<AttemptRecord> attempts() {
        return attempts;
    }

    public boolean isFailoverUsed() {
        return failoverUsed;
    }

    private void record(String outcome, String errorClass) {
        AttemptRecord attempt = new AttemptRecord();
        attempt.index = currentIndex;
        attempt.host = currentHost;
        attempt.outcome = outcome;
        attempt.errorClass = errorClass;
        attempt.elapsedMs = Telemetry.clampDurationMs(
                (clock.nanos() - attemptStartedNanos) / 1_000_000L);
        attempt.moved = false;
        if (currentIndex < attempts.size()) {
            attempts.set(currentIndex, attempt);
        } else {
            attempts.add(attempt);
        }
    }
}
