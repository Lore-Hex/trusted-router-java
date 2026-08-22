package com.trustedrouter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.internal.RequestRecorder.AttemptRecord;
import com.trustedrouter.internal.RequestRecorder.ConfiguredTimeouts;
import com.trustedrouter.internal.RequestRecorder.CounterIncrement;
import com.trustedrouter.internal.RequestRecorder.CounterKey;
import com.trustedrouter.internal.RequestRecorder.CounterUpdate;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

/**
 * The recorder's beacon surface (client telemetry contract v1, &sect;5.3/&sect;5.4):
 * the event and the EXACT counter increments derived from a scripted attempt
 * history, the {@code exhausted} rule, the stream hooks (TTFT, stream break,
 * abort), timeout phases and configured timeouts, the GET/POST gate, and the
 * pure vocabulary helpers — all with an injected clock so every number is
 * exact.
 */
final class RequestRecorderTest {
    private static final String APEX = "https://api.trustedrouter.com/v1";
    private static final String ALLY = "https://api.allyrouter.com/v1";
    private static final String REQUEST_ID = "rlog_0123456789abcdef0123456789abcdef";
    private static final ConfiguredTimeouts TIMEOUTS = new ConfiguredTimeouts(
            Long.valueOf(10_000L), Long.valueOf(60_000L), Long.valueOf(120_000L),
            Long.valueOf(120_000L));

    @Test void aRetriedFailoverCallProducesTheExactEventAndCounters() {
        FakeClock clock = new FakeClock();
        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = new RequestRecorder(sink, "chat_completions", "post", false,
                true, "model/a", TIMEOUTS, clock);

        clock.nowMs = 0L;
        recorder.beginAttempt(APEX);
        clock.nowMs = 25L;
        recorder.onResponse(503, response(503, "true", "1", REQUEST_ID));
        recorder.onMoved();
        clock.nowMs = 530L;
        recorder.beginAttempt(ALLY);
        clock.nowMs = 600L;
        recorder.onResponse(200, response(200, null, null, REQUEST_ID));
        recorder.finish();
        recorder.finish();

        assertThat(sink.events).hasSize(1);
        RequestRecorder.Event event = sink.events.get(0);
        assertThat(event.endpoint).isEqualTo("chat_completions");
        assertThat(event.method).isEqualTo("POST");
        assertThat(event.streaming).isFalse();
        assertThat(event.providerPinned).isTrue();
        assertThat(event.model).isEqualTo("model/a");
        assertThat(event.finalOutcome).isEqualTo("ok");
        assertThat(event.finalHttpStatus).isEqualTo(200);
        assertThat(event.totalMs).isEqualTo(600L);
        assertThat(event.ttftMs).isNull();
        assertThat(event.failoverUsed).isTrue();
        assertThat(event.timeoutPhase).isEqualTo("none");
        assertThat(event.configuredTimeoutMs).isEqualTo(120_000L);
        assertThat(event.attempts).hasSize(2);
        AttemptRecord first = event.attempts.get(0);
        assertThat(first.index).isZero();
        assertThat(first.host).isEqualTo("apex");
        assertThat(first.outcome).isEqualTo("http_error");
        assertThat(first.httpStatus).isEqualTo(503);
        assertThat(first.errorClass).isNull();
        assertThat(first.shouldRetry).isEqualTo("true");
        assertThat(first.retryAfterMs).isEqualTo(1_000L);
        assertThat(first.elapsedMs).isEqualTo(25L);
        assertThat(first.ttfbMs).isEqualTo(25L);
        assertThat(first.requestId).isEqualTo(REQUEST_ID);
        assertThat(first.moved).isTrue();
        AttemptRecord second = event.attempts.get(1);
        assertThat(second.index).isEqualTo(1);
        assertThat(second.host).isEqualTo("ally");
        assertThat(second.outcome).isEqualTo("ok");
        assertThat(second.httpStatus).isEqualTo(200);
        assertThat(second.shouldRetry).isEqualTo("absent");
        assertThat(second.retryAfterMs).isNull();
        assertThat(second.elapsedMs).isEqualTo(70L);
        assertThat(second.moved).isFalse();

        assertThat(sink.counters).hasSize(3);
        CounterUpdate request = sink.counters.get(0);
        assertThat(request.key).isEqualTo(new CounterKey("request", "chat_completions", false,
                "ally", "ok", null, "2xx", "none", false, true));
        assertThat(request.increment.requests).isEqualTo(1L);
        assertThat(request.increment.attempts).isEqualTo(2L);
        assertThat(request.increment.failoverUsed).isEqualTo(1L);
        assertThat(request.increment.firstAttemptSuccess).isZero();
        assertThat(request.increment.totalMsHist).containsExactly(entry("lt800"));
        assertThat(request.increment.firstEventMsHist).as("from the final ttfb of 70 ms")
                .containsExactly(entry("lt100"));
        CounterUpdate apexAttempt = sink.counters.get(1);
        assertThat(apexAttempt.key).isEqualTo(new CounterKey("attempt", "chat_completions", false,
                "apex", "http_error", null, "5xx", "none", false, true));
        assertThat(apexAttempt.increment.requests).isEqualTo(1L);
        assertThat(apexAttempt.increment.attempts).isEqualTo(1L);
        assertThat(apexAttempt.increment.failoverUsed).as("this attempt moved").isEqualTo(1L);
        assertThat(apexAttempt.increment.firstAttemptSuccess).isZero();
        assertThat(apexAttempt.increment.totalMsHist).isEmpty();
        CounterUpdate allyAttempt = sink.counters.get(2);
        assertThat(allyAttempt.key).isEqualTo(new CounterKey("attempt", "chat_completions", false,
                "ally", "ok", null, "2xx", "none", false, true));
        assertThat(allyAttempt.increment.failoverUsed).isZero();
    }

    @Test void exhaustedIsReportedOnlyForMultiAttemptFailuresWithSpentBudget() {
        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = recorder(sink, "POST");
        recorder.beginAttempt(APEX);
        recorder.onResponse(503);
        recorder.onMoved();
        recorder.beginAttempt(ALLY);
        recorder.onResponse(503);
        recorder.markExhausted(true);
        recorder.finish();
        assertThat(sink.events.get(0).finalOutcome).isEqualTo("exhausted");
        assertThat(sink.events.get(0).finalHttpStatus).isEqualTo(503);
        assertThat(sink.counters.get(0).key.outcome)
                .as("counter outcome is the final attempt's outcome, never exhausted")
                .isEqualTo("http_error");
        assertThat(sink.counters.get(0).key.errorClass).isNull();

        RecordingSink single = new RecordingSink();
        RequestRecorder one = recorder(single, "POST");
        one.beginAttempt(APEX);
        one.onResponse(503);
        one.markExhausted(true);
        one.finish();
        assertThat(single.events.get(0).finalOutcome).as("one attempt is never exhausted")
                .isEqualTo("http_error");

        RecordingSink rescued = new RecordingSink();
        RequestRecorder ok = recorder(rescued, "POST");
        ok.beginAttempt(APEX);
        ok.onTransportError(new SocketTimeoutException("connect timed out"));
        ok.onMoved();
        ok.beginAttempt(ALLY);
        ok.onResponse(200);
        ok.markExhausted(true);
        ok.finish();
        assertThat(rescued.events.get(0).finalOutcome).isEqualTo("ok");
        assertThat(rescued.counters.get(0).key.errorClass)
                .as("the request row carries the FIRST error class seen")
                .isEqualTo("connect_timeout");
        assertThat(rescued.counters.get(0).key.outcome).isEqualTo("ok");
    }

    @Test void streamHooksRecordTtftStreamBreakAndAbort() {
        FakeClock clock = new FakeClock();
        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = new RequestRecorder(sink, "chat_completions", "POST", true,
                false, "m", TIMEOUTS, clock);
        clock.nowMs = 0L;
        recorder.beginAttempt(APEX);
        clock.nowMs = 40L;
        recorder.onResponse(200, response(200, null, null, REQUEST_ID));
        clock.nowMs = 90L;
        recorder.onFirstEvent();
        clock.nowMs = 150L;
        recorder.onFirstEvent();
        assertThat(recorder.ttftMs()).as("first event only, once").isEqualTo(90L);
        clock.nowMs = 300L;
        recorder.onTransportError(new ProtocolException("unexpected end of stream"), true, true);
        recorder.finish();

        RequestRecorder.Event event = sink.events.get(0);
        assertThat(event.finalOutcome).isEqualTo("stream_broken");
        assertThat(event.ttftMs).isEqualTo(90L);
        assertThat(event.totalMs).isEqualTo(300L);
        assertThat(event.attempts).hasSize(1);
        AttemptRecord attempt = event.attempts.get(0);
        assertThat(attempt.outcome).isEqualTo("stream_broken");
        assertThat(attempt.errorClass).isEqualTo("protocol_error");
        assertThat(attempt.httpStatus).as("kept from the opened response").isEqualTo(200);
        assertThat(attempt.ttfbMs).isEqualTo(40L);
        assertThat(attempt.requestId).isEqualTo(REQUEST_ID);
        assertThat(attempt.elapsedMs).isEqualTo(300L);
        assertThat(sink.counters.get(0).key).isEqualTo(new CounterKey("request",
                "chat_completions", true, "apex", "stream_broken", "protocol_error", "2xx",
                "none", false, false));
        assertThat(sink.counters.get(0).increment.firstEventMsHist).as("ttft bucket")
                .containsExactly(entry("lt100"));

        RecordingSink aborted = new RecordingSink();
        RequestRecorder closing = new RequestRecorder(aborted, "chat_completions", "POST", true,
                false, "m", TIMEOUTS, clock);
        clock.nowMs = 0L;
        closing.beginAttempt(APEX);
        closing.onResponse(200, response(200, null, null, REQUEST_ID));
        closing.onFirstEvent();
        clock.nowMs = 75L;
        closing.onAborted();
        closing.finish();
        assertThat(aborted.events.get(0).finalOutcome).isEqualTo("aborted");
        assertThat(aborted.events.get(0).attempts.get(0).httpStatus).isEqualTo(200);
        assertThat(aborted.events.get(0).attempts.get(0).requestId).isEqualTo(REQUEST_ID);
        assertThat(aborted.events.get(0).attempts.get(0).elapsedMs).isEqualTo(75L);
        assertThat(aborted.counters.get(1).key.outcome).isEqualTo("aborted");

        RecordingSink early = new RecordingSink();
        RequestRecorder beforeAnyResponse = recorder(early, "POST");
        beforeAnyResponse.beginAttempt(APEX);
        beforeAnyResponse.onAborted();
        beforeAnyResponse.finish();
        assertThat(early.events.get(0).attempts.get(0).outcome).isEqualTo("aborted");
        assertThat(early.events.get(0).attempts.get(0).httpStatus).isNull();
        assertThat(early.events.get(0).finalHttpStatus).isNull();
    }

    @Test void aBodyFailureBeforeTheFirstEventIsATransportErrorNotAStreamBreak() {
        // Mirrors the Python reference: response_opened=True, body_started=False.
        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = recorder(sink, "POST");
        recorder.beginAttempt(APEX);
        recorder.onResponse(200, response(200, null, null, REQUEST_ID));
        recorder.onTransportError(new IOException("unexpected end of stream"), true, false);
        recorder.finish();
        AttemptRecord attempt = sink.events.get(0).attempts.get(0);
        assertThat(attempt.outcome).isEqualTo("transport_error");
        assertThat(attempt.errorClass).isEqualTo("io_error");
        assertThat(attempt.httpStatus).isEqualTo(200);
        assertThat(attempt.ttfbMs).isNotNull();
    }

    @Test void timeoutPhasesFollowTheFailureAndTheBody() {
        assertThat(Telemetry.timeoutPhase(new SocketTimeoutException("connect timed out")))
                .isEqualTo("connect");
        assertThat(Telemetry.timeoutPhase(new SocketTimeoutException("Read timed out")))
                .isEqualTo("first_byte");
        assertThat(Telemetry.timeoutPhase(new InterruptedIOException("timeout")))
                .as("OkHttp's whole-call timeout").isEqualTo("total");
        assertThat(Telemetry.timeoutPhase(new ProtocolException("x"))).isEqualTo("none");
        assertThat(Telemetry.timeoutPhase(new InterruptedIOException("retry interrupted")))
                .isEqualTo("none");

        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = recorder(sink, "POST");
        recorder.beginAttempt(APEX);
        recorder.onResponse(200, response(200, null, null, null));
        recorder.onTransportError(new SocketTimeoutException("Read timed out"), true, true);
        recorder.finish();
        AttemptRecord stalled = sink.events.get(0).attempts.get(0);
        assertThat(stalled.outcome).isEqualTo("timeout");
        assertThat(stalled.errorClass).as("a read stall after the first event").isEqualTo("stream_stalled");
        assertThat(sink.events.get(0).timeoutPhase).isEqualTo("idle");
        assertThat(sink.events.get(0).configuredTimeoutMs).as("idle reads the read timeout")
                .isEqualTo(60_000L);
        assertThat(sink.counters.get(0).key.timeoutPhase).isEqualTo("idle");
        assertThat(sink.counters.get(0).key.timeoutFloorMet).as("60 s >= the 30 s idle floor")
                .isTrue();

        RecordingSink connect = new RecordingSink();
        RequestRecorder connecting = recorder(connect, "POST");
        connecting.beginAttempt(APEX);
        connecting.onTransportError(new SocketTimeoutException("connect timed out"));
        connecting.finish();
        assertThat(connect.events.get(0).timeoutPhase).isEqualTo("connect");
        assertThat(connect.events.get(0).configuredTimeoutMs).isEqualTo(10_000L);
        assertThat(connect.counters.get(0).key.timeoutFloorMet).isTrue();
        assertThat(connect.counters.get(0).key.errorClass).isEqualTo("connect_timeout");
        assertThat(connect.events.get(0).attempts.get(0).httpStatus).isNull();

        RecordingSink total = new RecordingSink();
        RequestRecorder whole = recorder(total, "POST");
        whole.beginAttempt(APEX);
        whole.onTransportError(new InterruptedIOException("timeout"));
        whole.finish();
        assertThat(total.events.get(0).timeoutPhase).isEqualTo("total");
        assertThat(total.events.get(0).configuredTimeoutMs).isEqualTo(120_000L);
        assertThat(total.counters.get(0).key.timeoutFloorMet).as("total never meets a floor")
                .isFalse();
    }

    @Test void configuredTimeoutsFollowThePhaseAndTheFloor() {
        assertThat(TIMEOUTS.forPhase("connect")).isEqualTo(10_000L);
        assertThat(TIMEOUTS.forPhase("first_byte")).isEqualTo(60_000L);
        assertThat(TIMEOUTS.forPhase("idle")).isEqualTo(60_000L);
        assertThat(TIMEOUTS.forPhase("total")).isEqualTo(120_000L);
        assertThat(TIMEOUTS.forPhase("none")).isEqualTo(120_000L);
        assertThat(ConfiguredTimeouts.NONE.forPhase("connect")).isNull();
        assertThat(new ConfiguredTimeouts(Long.valueOf(0L), Long.valueOf(-1L),
                Long.valueOf(7_200_000L), null).forPhase("connect")).isNull();
        assertThat(new ConfiguredTimeouts(Long.valueOf(0L), Long.valueOf(-1L),
                Long.valueOf(7_200_000L), null).forPhase("first_byte")).isNull();
        assertThat(new ConfiguredTimeouts(Long.valueOf(0L), Long.valueOf(-1L),
                Long.valueOf(7_200_000L), null).forPhase("total")).isEqualTo(3_600_000L);

        assertThat(Telemetry.timeoutFloorMet("connect", Long.valueOf(10_000L))).isTrue();
        assertThat(Telemetry.timeoutFloorMet("connect", Long.valueOf(9_999L))).isFalse();
        assertThat(Telemetry.timeoutFloorMet("first_byte", Long.valueOf(60_000L))).isTrue();
        assertThat(Telemetry.timeoutFloorMet("first_byte", Long.valueOf(59_999L))).isFalse();
        assertThat(Telemetry.timeoutFloorMet("idle", Long.valueOf(30_000L))).isTrue();
        assertThat(Telemetry.timeoutFloorMet("idle", Long.valueOf(29_999L))).isFalse();
        assertThat(Telemetry.timeoutFloorMet("total", Long.valueOf(3_600_000L))).isFalse();
        assertThat(Telemetry.timeoutFloorMet("none", Long.valueOf(3_600_000L))).isFalse();
        assertThat(Telemetry.timeoutFloorMet("connect", null)).isFalse();
    }

    @Test void onlyGetAndPostAreEmitted() {
        for (String method : new String[] {"PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}) {
            RecordingSink sink = new RecordingSink();
            RequestRecorder recorder = recorder(sink, method);
            recorder.beginAttempt(APEX);
            assertThat(recorder.headerValue()).as("the header channel still rides %s", method)
                    .isEqualTo("v=1;a=0;s=0");
            recorder.onResponse(200);
            recorder.finish();
            assertThat(sink.events).as("%s is outside the schema", method).isEmpty();
            assertThat(sink.counters).isEmpty();
        }
        for (String method : new String[] {"GET", "post"}) {
            RecordingSink sink = new RecordingSink();
            RequestRecorder recorder = recorder(sink, method);
            recorder.beginAttempt(APEX);
            recorder.onResponse(200);
            recorder.finish();
            assertThat(sink.events).hasSize(1);
            assertThat(sink.events.get(0).method).isEqualTo(method.toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Test void modelOutsideTheGrammarIsNullAndNeverOnTheWire() {
        RecordingSink sink = new RecordingSink();
        RequestRecorder recorder = new RequestRecorder(sink, "chat_completions", "POST", false,
                false, "my model with spaces and a secret", TIMEOUTS, null);
        recorder.beginAttempt(APEX);
        recorder.onResponse(200);
        recorder.finish();
        assertThat(sink.events.get(0).model).isNull();

        RecordingSink valid = new RecordingSink();
        RequestRecorder accepted = new RequestRecorder(valid, "chat_completions", "POST", false,
                false, "~kimi/latest:free@v2", TIMEOUTS, null);
        accepted.beginAttempt(APEX);
        accepted.onResponse(200);
        accepted.finish();
        assertThat(valid.events.get(0).model).isEqualTo("~kimi/latest:free@v2");
    }

    @Test void endpointMappingMirrorsThePythonReference() {
        assertThat(Telemetry.endpointEnum("/chat/completions")).isEqualTo("chat_completions");
        assertThat(Telemetry.endpointEnum("chat/completions")).isEqualTo("chat_completions");
        assertThat(Telemetry.endpointEnum("/chat/completions/")).isEqualTo("chat_completions");
        assertThat(Telemetry.endpointEnum("/chat/completions?x=1")).isEqualTo("chat_completions");
        assertThat(Telemetry.endpointEnum("/messages")).isEqualTo("messages");
        assertThat(Telemetry.endpointEnum("/responses")).isEqualTo("responses");
        assertThat(Telemetry.endpointEnum("/responses/input_tokens")).isEqualTo("inference_other");
        assertThat(Telemetry.endpointEnum("/embeddings")).isEqualTo("embeddings");
        assertThat(Telemetry.endpointEnum("/images")).isEqualTo("images");
        assertThat(Telemetry.endpointEnum("/images/generations")).isEqualTo("images");
        assertThat(Telemetry.endpointEnum("/imagesx")).isEqualTo("inference_other");
        assertThat(Telemetry.endpointEnum("/videos/jobs")).isEqualTo("videos");
        assertThat(Telemetry.endpointEnum("/models")).isEqualTo("models");
        assertThat(Telemetry.endpointEnum("/fusion/run")).isEqualTo("fusion");
        assertThat(Telemetry.endpointEnum("/completions")).isEqualTo("inference_other");
        assertThat(Telemetry.endpointEnum("")).isEqualTo("inference_other");
        assertThat(Telemetry.endpointEnum(null)).isEqualTo("inference_other");
    }

    @Test void latencyBucketsStatusClassesAndPlatformEnumsAreClosed() {
        assertThat(Telemetry.latencyBucket(-5L)).isEqualTo("lt100");
        assertThat(Telemetry.latencyBucket(99L)).isEqualTo("lt100");
        assertThat(Telemetry.latencyBucket(100L)).isEqualTo("lt200");
        assertThat(Telemetry.latencyBucket(799L)).isEqualTo("lt800");
        assertThat(Telemetry.latencyBucket(51_200L)).isEqualTo("lt102400");
        assertThat(Telemetry.latencyBucket(102_399L)).isEqualTo("lt102400");
        assertThat(Telemetry.latencyBucket(102_400L)).isEqualTo("ge102400");
        assertThat(Telemetry.latencyBucket(Long.MAX_VALUE)).isEqualTo("ge102400");

        assertThat(Telemetry.statusClass(null)).isEqualTo("none");
        assertThat(Telemetry.statusClass(Integer.valueOf(200))).isEqualTo("2xx");
        assertThat(Telemetry.statusClass(Integer.valueOf(299))).isEqualTo("2xx");
        assertThat(Telemetry.statusClass(Integer.valueOf(404))).isEqualTo("4xx");
        assertThat(Telemetry.statusClass(Integer.valueOf(429))).isEqualTo("429");
        assertThat(Telemetry.statusClass(Integer.valueOf(503))).isEqualTo("5xx");
        assertThat(Telemetry.statusClass(Integer.valueOf(302))).isEqualTo("none");
        assertThat(Telemetry.statusClass(Integer.valueOf(600))).isEqualTo("none");

        assertThat(Telemetry.osEnum("Mac OS X", "")).isEqualTo("macos");
        assertThat(Telemetry.osEnum("Linux", "OpenJDK 64-Bit Server VM")).isEqualTo("linux");
        assertThat(Telemetry.osEnum("Linux", "Dalvik Android Runtime")).isEqualTo("android");
        assertThat(Telemetry.osEnum("Windows 11", "")).isEqualTo("windows");
        assertThat(Telemetry.osEnum("FreeBSD", "")).isEqualTo("freebsd");
        assertThat(Telemetry.osEnum("SunOS", "")).isEqualTo("other");
        assertThat(Telemetry.osEnum(null, null)).isEqualTo("other");
        assertThat(Telemetry.archEnum("aarch64")).isEqualTo("arm64");
        assertThat(Telemetry.archEnum("arm64")).isEqualTo("arm64");
        assertThat(Telemetry.archEnum("amd64")).isEqualTo("x64");
        assertThat(Telemetry.archEnum("x86_64")).isEqualTo("x64");
        assertThat(Telemetry.archEnum("x86")).isEqualTo("x32");
        assertThat(Telemetry.archEnum("i686")).isEqualTo("x32");
        assertThat(Telemetry.archEnum("armv7l")).isEqualTo("arm");
        assertThat(Telemetry.archEnum("wasm32")).isEqualTo("wasm");
        assertThat(Telemetry.archEnum("riscv64")).isEqualTo("other");
        assertThat(Telemetry.archEnum(null)).isEqualTo("other");

        JsonObject identity = Telemetry.sdkIdentity("0.6.0", "1.8.0_452", "Mac OS X", "", "aarch64");
        assertThat(identity.get("version").getAsString()).isEqualTo("0.6.0");
        assertThat(identity.get("runtime").getAsString()).isEqualTo("java/1.8.0-452");
        JsonObject fallback = Telemetry.sdkIdentity("v1", "", "", "", "");
        assertThat(fallback.get("version").getAsString()).isEqualTo("0.0.0");
        assertThat(fallback.get("runtime").getAsString()).isEqualTo("java/0");
        assertThat(fallback.get("os").getAsString()).isEqualTo("other");
        assertThat(fallback.get("arch").getAsString()).isEqualTo("other");
    }

    @Test void finishNeverEmitsWithoutAnAttemptAndSurvivesAHostileSink() {
        RecordingSink sink = new RecordingSink();
        RequestRecorder untouched = recorder(sink, "POST");
        untouched.finish();
        assertThat(sink.events).isEmpty();

        RequestRecorder headerOnly = new RequestRecorder(false);
        headerOnly.beginAttempt(APEX);
        headerOnly.onResponse(200);
        headerOnly.finish();
        assertThat(headerOnly.isFinished()).isTrue();

        TelemetrySink hostile = new TelemetrySink() {
            @Override public void onRequest(
                    RequestRecorder.Event event, List<CounterUpdate> counters) {
                throw new IllegalStateException("hostile sink");
            }
        };
        RequestRecorder recorder = new RequestRecorder(hostile, "chat_completions", "POST", false,
                false, "m", TIMEOUTS, null);
        recorder.beginAttempt(APEX);
        recorder.onResponse(200);
        recorder.finish();
        assertThat(recorder.isFinished()).isTrue();
    }

    @Test void providerPinnedAndModelAreReadFromTheBodyAsInPython() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "model/a");
        assertThat(Transport.providerPinned(body)).isFalse();
        assertThat(Transport.modelOf(body)).isEqualTo("model/a");
        JsonObject provider = new JsonObject();
        provider.addProperty("allow_fallbacks", false);
        body.add("provider", provider);
        assertThat(Transport.providerPinned(body)).isTrue();
        provider.addProperty("allow_fallbacks", true);
        assertThat(Transport.providerPinned(body)).isFalse();
        provider.addProperty("allow_fallbacks", "false");
        assertThat(Transport.providerPinned(body)).as("only a JSON boolean pins").isFalse();
        body.addProperty("model", 7);
        assertThat(Transport.modelOf(body)).isNull();
        assertThat(Transport.providerPinned(null)).isFalse();
        assertThat(Transport.modelOf(null)).isNull();
    }

    @Test void abortDetectionCoversCancellationAndInterruptionOnly() {
        assertThat(Transport.isAbort(new InternalException(499, "cancelled", null))).isTrue();
        InterruptedIOException interrupted = new InterruptedIOException("retry interrupted");
        assertThat(Transport.isAbort(new InternalException(503, "x", null, interrupted))).isTrue();
        assertThat(Transport.isAbort(new InternalException(503, "x", null,
                new InterruptedIOException("timeout")))).as("a call timeout is not an abort")
                .isFalse();
        assertThat(Transport.isAbort(new InternalException(503, "unavailable", null,
                new SocketTimeoutException("connect timed out")))).isFalse();
        assertThat(Transport.isAbort(new IllegalStateException("x"))).isFalse();
    }

    private static RequestRecorder recorder(TelemetrySink sink, String method) {
        return new RequestRecorder(sink, "chat_completions", method, false, false, "m", TIMEOUTS,
                null);
    }

    private static Response response(
            int status, String shouldRetry, String retryAfter, String requestId) {
        Response.Builder builder = new Response.Builder()
                .request(new Request.Builder().url(APEX + "/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("status");
        if (shouldRetry != null) {
            builder.header("x-should-retry", shouldRetry);
        }
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter);
        }
        if (requestId != null) {
            builder.header("x-request-id", requestId);
        }
        return builder.build();
    }

    private static java.util.Map.Entry<String, Long> entry(String bucket) {
        return new java.util.AbstractMap.SimpleEntry<String, Long>(bucket, Long.valueOf(1L));
    }

    static final class RecordingSink implements TelemetrySink {
        final List<RequestRecorder.Event> events = new ArrayList<RequestRecorder.Event>();
        final List<CounterUpdate> counters = new ArrayList<CounterUpdate>();

        @Override public void onRequest(RequestRecorder.Event event, List<CounterUpdate> updates) {
            events.add(event);
            counters.addAll(updates);
        }
    }

    static final class FakeClock implements RequestRecorder.NanoClock {
        long nowMs;

        @Override public long nanos() {
            return nowMs * 1_000_000L;
        }
    }

    @SuppressWarnings("unused")
    private static CounterIncrement unused() {
        return new CounterIncrement();
    }
}
