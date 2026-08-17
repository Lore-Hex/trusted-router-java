package com.trustedrouter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * Unit surface of the header channel (client telemetry contract v1):
 * transport-error classification from real exception types, the §6.3 opt-out
 * precedence with an injected environment, the §3.2 grammar guard, and the
 * long-only duration clamp.
 */
final class TelemetryUnitTest {
    private static final String APEX = "https://api.trustedrouter.com/v1";
    private static final String CONTROL = "https://trustedrouter.com/v1";

    // --- §5.2 ErrorClass from the exception the HTTP client actually threw ---

    @Test void classifiesTheCommonTransportFailures() {
        assertThat(Telemetry.classifyTransportError(
                new UnknownHostException("api.trustedrouter.com"))).isEqualTo("dns");
        assertThat(Telemetry.classifyTransportError(
                new SSLHandshakeException("PKIX path building failed"))).isEqualTo("tls");
        assertThat(Telemetry.classifyTransportError(
                new ConnectException("Connection refused"))).isEqualTo("connect_refused");
        assertThat(Telemetry.classifyTransportError(
                new ConnectException("Network is unreachable"))).isEqualTo("connect_error");
        assertThat(Telemetry.classifyTransportError(
                new SocketTimeoutException("Connect timed out"))).isEqualTo("connect_timeout");
        assertThat(Telemetry.classifyTransportError(
                new SocketTimeoutException("Read timed out"))).isEqualTo("read_timeout");
        assertThat(Telemetry.classifyTransportError(
                new SocketTimeoutException("timeout"))).isEqualTo("read_timeout");
        assertThat(Telemetry.classifyTransportError(
                new SocketException("Connection reset"))).isEqualTo("reset");
        assertThat(Telemetry.classifyTransportError(
                new ProtocolException("unexpected status line"))).isEqualTo("protocol_error");
        assertThat(Telemetry.classifyTransportError(
                new IOException("canceled"))).isEqualTo("io_error");
        assertThat(Telemetry.classifyTransportError(
                new RuntimeException("not io at all"))).isEqualTo("unknown");
    }

    @Test void walksTheCauseChainTheWayOkHttpWrapsFailures() {
        // OkHttp frequently surfaces a wrapper IOException whose cause carries
        // the real failure; classification must see through it.
        IOException wrapped = new IOException("exchange failed");
        wrapped.initCause(new UnknownHostException("no such host"));
        assertThat(Telemetry.classifyTransportError(wrapped)).isEqualTo("dns");

        IOException tls = new IOException("handshake");
        tls.initCause(new SSLHandshakeException("bad cert"));
        assertThat(Telemetry.classifyTransportError(tls)).isEqualTo("tls");

        // A timeout anywhere in the chain outranks the wrapper's class.
        IOException slow = new IOException("call failed");
        slow.initCause(new SocketTimeoutException("connect timed out"));
        assertThat(Telemetry.classifyTransportError(slow)).isEqualTo("connect_timeout");
    }

    @Test void timeoutOutcomeCoversSocketAndCallTimeoutsButNotInterruption() {
        assertThat(Telemetry.isTimeout(new SocketTimeoutException("Read timed out"))).isTrue();
        // OkHttp's callTimeout surfaces InterruptedIOException("timeout")
        // from okio.AsyncTimeout; its class alone carries no error detail,
        // so the pair is outcome=timeout with class io_error.
        assertThat(Telemetry.isTimeout(new InterruptedIOException("timeout"))).isTrue();
        assertThat(Telemetry.classifyTransportError(new InterruptedIOException("timeout")))
                .isEqualTo("io_error");
        // A plain interruption is an abort, not a timeout — OkHttp itself
        // only recovers SocketTimeoutException as a timeout.
        assertThat(Telemetry.isTimeout(new InterruptedIOException("retry interrupted")))
                .isFalse();
        assertThat(Telemetry.isTimeout(new ConnectException("Connection refused"))).isFalse();
        assertThat(Telemetry.isTimeout(new UnknownHostException("x"))).isFalse();
        IOException wrapped = new IOException("call failed");
        wrapped.initCause(new SocketTimeoutException("timeout"));
        assertThat(Telemetry.isTimeout(wrapped)).isTrue();
    }

    @Test void attemptIndexesPastTheContractRangeSendNothing() {
        RequestRecorder recorder = new RequestRecorder(false);
        for (int attempt = 0; attempt <= 99; attempt++) {
            recorder.beginAttempt(APEX);
            recorder.onResponse(503);
        }
        recorder.beginAttempt(APEX);
        // a=100 would pass the value regex but §3.2 bounds a to 0..99.
        assertThat(recorder.attempts()).hasSize(100);
        assertThat(recorder.headerValue()).isNull();
    }

    // --- §6.3 opt-out precedence, environment injected, never mutated ---

    @Test void explicitOptionBeatsEveryEnvironmentVariable() {
        assertThat(Telemetry.resolveEnabled(
                Boolean.FALSE, APEX, CONTROL, env("TRUSTEDROUTER_TELEMETRY", "1"))).isFalse();
        assertThat(Telemetry.resolveEnabled(
                Boolean.TRUE, APEX, CONTROL, env("TRUSTEDROUTER_TELEMETRY", "0"))).isTrue();
        assertThat(Telemetry.resolveEnabled(
                Boolean.TRUE, APEX, CONTROL, env("DO_NOT_TRACK", "1"))).isTrue();
    }

    @Test void telemetryEnvBeatsDoNotTrack() {
        Map<String, String> both = env("TRUSTEDROUTER_TELEMETRY", "1");
        both.put("DO_NOT_TRACK", "1");
        assertThat(Telemetry.resolveEnabled(null, APEX, CONTROL, both)).isTrue();
        assertThat(Telemetry.resolveEnabled(
                null, APEX, CONTROL, env("TRUSTEDROUTER_TELEMETRY", "0"))).isFalse();
    }

    @Test void everyDocumentedDisableAndEnableTokenIsHonoured() {
        for (String token : new String[] {"0", "false", "off", "no", " OFF "}) {
            assertThat(Telemetry.resolveEnabled(
                    null, APEX, CONTROL, env("TRUSTEDROUTER_TELEMETRY", token)))
                    .as("disable token %s", token).isFalse();
        }
        for (String token : new String[] {"1", "true", "on", "yes", " YES "}) {
            assertThat(Telemetry.resolveEnabled(
                    null, "https://my.internal/v1", "https://my.internal",
                    env("TRUSTEDROUTER_TELEMETRY", token)))
                    .as("enable token %s", token).isTrue();
        }
        // An unrecognised token falls through to the default resolution.
        assertThat(Telemetry.resolveEnabled(
                null, APEX, CONTROL, env("TRUSTEDROUTER_TELEMETRY", "maybe"))).isTrue();
    }

    @Test void doNotTrackDisablesWhenNothingElseSpeaks() {
        assertThat(Telemetry.resolveEnabled(null, APEX, CONTROL, env("DO_NOT_TRACK", "1")))
                .isFalse();
        assertThat(Telemetry.resolveEnabled(null, APEX, CONTROL, env("DO_NOT_TRACK", "0")))
                .isTrue();
    }

    @Test void defaultIsOnOnlyForTrustedRouterHostsOnBothPlanes() {
        Map<String, String> empty = new HashMap<String, String>();
        assertThat(Telemetry.resolveEnabled(null, APEX, CONTROL, empty)).isTrue();
        assertThat(Telemetry.resolveEnabled(
                null, "https://api.allyrouter.com/v1", CONTROL, empty)).isTrue();
        // Custom inference base: default off.
        assertThat(Telemetry.resolveEnabled(
                null, "https://my.internal/v1", CONTROL, empty)).isFalse();
        // Custom control plane: default off even with the apex base.
        assertThat(Telemetry.resolveEnabled(
                null, APEX, "https://control.internal/v1", empty)).isFalse();
        // Control must be https trustedrouter.com or a subdomain.
        assertThat(Telemetry.resolveEnabled(
                null, APEX, "http://trustedrouter.com/v1", empty)).isFalse();
        assertThat(Telemetry.resolveEnabled(
                null, APEX, "https://eu.trustedrouter.com/v1", empty)).isTrue();
    }

    // --- §3.2 header assembly: golden vector, grammar guard, byte cap ---

    @Test void theDocumentedRetryExampleSerializesByteForByte() {
        // The contract's §3.2 retry example, as corrected upstream
        // (quill-router#645: a connect timeout is po=timeout, matching the
        // Python reference): every key, in order, with the exact separators,
        // produced entirely by real recorder state — only the clock is
        // injected so pm/sm are exact.
        FakeClock clock = new FakeClock();
        RequestRecorder recorder = new RequestRecorder(true, clock);
        clock.nowMs = 0L;
        recorder.beginAttempt(APEX);
        clock.nowMs = 10_012L;
        recorder.onTransportError(new SocketTimeoutException("connect timed out"));
        recorder.onMoved();
        clock.nowMs = 10_530L;
        recorder.beginAttempt("https://api.allyrouter.com/v1");

        assertThat(recorder.headerValue()).isEqualTo(
                "v=1;a=1;po=timeout;pc=connect_timeout;ph=apex;pm=10012;sm=10530;s=1;fo=1");
    }

    @Test void aForcedRetryAfterAnOkResponseMapsPoAndPcToNone() {
        // §3.2 closes po over {none, http_error, transport_error, timeout,
        // stream_broken}; a forced retry after a sub-400 response
        // (x-should-retry: true) must not leak the out-of-vocab "ok".
        RequestRecorder recorder = new RequestRecorder(false);
        recorder.beginAttempt(APEX);
        recorder.onResponse(200);
        recorder.beginAttempt(APEX);

        assertThat(recorder.headerValue()).matches(
                "^v=1;a=1;po=none;pc=none;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=0;fo=0$");
    }

    @Test void firstAttemptVectorsAreExact() {
        RequestRecorder streaming = new RequestRecorder(true);
        streaming.beginAttempt(APEX);
        assertThat(streaming.headerValue()).isEqualTo("v=1;a=0;s=1");

        RequestRecorder buffered = new RequestRecorder(false);
        buffered.beginAttempt(APEX);
        assertThat(buffered.headerValue()).isEqualTo("v=1;a=0;s=0");
    }

    @Test void aCustomAttemptHostProducesNoHeaderAtAll() {
        RequestRecorder recorder = new RequestRecorder(false);
        recorder.beginAttempt("https://my.internal/v1");
        assertThat(recorder.headerValue()).isNull();
    }

    @Test void anOutOfGrammarValueSendsNothingAndNeverThrows() {
        // Unreachable through the engine (every value comes from a closed
        // internal vocabulary), so the guard is exercised by tampering the
        // recorded state directly. An out-of-vocab OUTCOME is normalised to
        // po=none by the §3.2 vocabulary mapping, so the regex guard's
        // remaining live inputs are the error class and host fields.
        RequestRecorder recorder = retriedRecorder();
        recorder.attempts().get(0).errorClass = "Not-Valid!";
        assertThat(recorder.headerValue()).isNull();

        recorder = retriedRecorder();
        recorder.attempts().get(0).host = "a_value_longer_than_twenty_four_characters";
        assertThat(recorder.headerValue()).isNull();
    }

    @Test void theByteCapHoldsAtTheLongestProducibleHeader() {
        // The longest header production can emit: attempt index 99 (the §3.2
        // maximum), the longest outcome/class pair a real exception produces
        // (transport_error/protocol_error), the longest host enum
        // (europe_west4), and both durations at the 3600000 clamp — every
        // value produced by real recorder calls, only the clock injected.
        FakeClock clock = new FakeClock();
        RequestRecorder recorder = new RequestRecorder(true, clock);
        String region = "https://api-europe-west4.quillrouter.com/v1";
        for (int attempt = 0; attempt <= 98; attempt++) {
            clock.nowMs = attempt * 3_600_000L;
            recorder.beginAttempt(region);
            clock.nowMs = (attempt + 1) * 3_600_000L;
            recorder.onTransportError(new ProtocolException("unexpected status line"));
        }
        recorder.onMoved();
        clock.nowMs = 99L * 3_600_000L;
        recorder.beginAttempt(region);

        String header = recorder.headerValue();
        assertThat(header).isEqualTo("v=1;a=99;po=transport_error;pc=protocol_error"
                + ";ph=europe_west4;pm=3600000;sm=3600000;s=1;fo=1");
        assertThat(header.length()).isLessThanOrEqualTo(160);
    }

    @Test void aWellFormedRetryHeaderStaysComfortablyUnderTheCap() {
        FakeClock clock = new FakeClock();
        RequestRecorder recorder = new RequestRecorder(true, clock);
        clock.nowMs = 0L;
        recorder.beginAttempt(APEX);
        clock.nowMs = 3_600_000L;
        recorder.onTransportError(new SocketTimeoutException("connect timed out"));
        recorder.onMoved();
        clock.nowMs = 7_200_000L;
        recorder.beginAttempt("https://api.allyrouter.com/v1");
        String header = recorder.headerValue();
        assertThat(header).isNotNull();
        assertThat(header.length()).isLessThanOrEqualTo(160);
        assertThat(header).contains("pm=3600000").contains("sm=3600000");
    }

    // --- durations: long arithmetic, explicit clamp ---

    @Test void durationsClampInLongArithmetic() {
        assertThat(Telemetry.clampDurationMs(-1L)).isZero();
        assertThat(Telemetry.clampDurationMs(0L)).isZero();
        assertThat(Telemetry.clampDurationMs(10_012L)).isEqualTo(10_012L);
        assertThat(Telemetry.clampDurationMs(3_600_000L)).isEqualTo(3_600_000L);
        assertThat(Telemetry.clampDurationMs(3_600_001L)).isEqualTo(3_600_000L);
        assertThat(Telemetry.clampDurationMs(Long.MAX_VALUE)).isEqualTo(3_600_000L);
        assertThat(Telemetry.clampDurationMs(Long.MIN_VALUE)).isZero();
    }

    private static RequestRecorder retriedRecorder() {
        RequestRecorder recorder = new RequestRecorder(false);
        recorder.beginAttempt(APEX);
        recorder.onResponse(503);
        recorder.onMoved();
        recorder.beginAttempt("https://api.allyrouter.com/v1");
        return recorder;
    }

    private static Map<String, String> env(String key, String value) {
        Map<String, String> environ = new HashMap<String, String>();
        environ.put(key, value);
        return environ;
    }

    private static final class FakeClock implements RequestRecorder.NanoClock {
        long nowMs;

        @Override
        public long nanos() {
            return nowMs * 1_000_000L;
        }
    }
}
