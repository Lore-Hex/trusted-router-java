package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.internal.RequestRecorder.CounterIncrement;
import com.trustedrouter.internal.RequestRecorder.CounterKey;
import com.trustedrouter.internal.TelemetryReporter;
import com.trustedrouter.models.ChatCompletionChunk;
import com.trustedrouter.requests.ChatRequest;
import com.trustedrouter.streaming.EventStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The beacon channel end to end (client telemetry contract v1, &sect;6.4),
 * driving the REAL engine against a local inference server (requests carry
 * the SDK's default TrustedRouter hostnames and an application interceptor
 * reroutes them, as in {@code ClientTelemetryHeaderTest}) with a SECOND local
 * server standing in for the control plane's {@code /client-events}. The
 * engine server and the engine's interceptor never see a beacon: the beacon
 * rides the reporter's own single-shot client.
 */
final class ClientTelemetryBeaconTest {
    private static final String REQUEST_ID = "rlog_0123456789abcdef0123456789abcdef";

    private MockWebServer engine;
    private MockWebServer beacon;
    private BeaconDispatcher control;
    private final List<TrustedRouterClient> clients = new ArrayList<TrustedRouterClient>();

    @BeforeEach void start() throws Exception {
        engine = new MockWebServer();
        engine.start();
        beacon = new MockWebServer();
        control = new BeaconDispatcher();
        beacon.setDispatcher(control);
        beacon.start();
    }

    @AfterEach void stop() throws Exception {
        for (TrustedRouterClient client : clients) {
            client.close();
        }
        engine.shutdown();
        beacon.shutdown();
    }

    @Test void theBeaconRidesItsOwnClientAndTheEngineNeverSeesIt() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")).setHeader("x-request-id", REQUEST_ID));
        TrustedRouterClient client = client(wire, 0, 1.0d);

        client.chatCompletions(ChatRequest.builder().model("model/a").message("user", "hi").build());
        TelemetryReporter reporter = client.transport().telemetryReporter();
        assertThat(reporter).isNotNull();
        assertThat(reporter.flushNow()).isTrue();

        RecordedRequest posted = control.requests.get(0);
        assertThat(posted.getMethod()).isEqualTo("POST");
        assertThat(posted.getPath()).isEqualTo("/v1/client-events");
        assertThat(posted.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(posted.getHeader("X-TrustedRouter-Workspace")).isEqualTo("ws_test");
        assertThat(posted.getHeader("Content-Type")).startsWith("application/json");
        assertThat(posted.getHeader("User-Agent")).startsWith("trusted-router-java/");
        assertThat(posted.getHeader("x-tr-client")).as("the beacon is never traced").isNull();
        assertThat(posted.getHeader("Idempotency-Key")).isNull();

        assertThat(engine.getRequestCount()).isEqualTo(1);
        assertThat(engine.takeRequest().getPath()).doesNotContain("client-events");
        assertThat(wire.paths).as("the engine's interceptor never saw the beacon")
                .containsExactly("/v1/chat/completions");

        JsonObject batch = control.batches.get(0);
        assertSchemaKeysOnly(batch);
        JsonObject event = batch.getAsJsonArray("events").get(0).getAsJsonObject();
        assertThat(event.get("endpoint").getAsString()).isEqualTo("chat_completions");
        assertThat(event.get("method").getAsString()).isEqualTo("POST");
        assertThat(event.get("streaming").getAsBoolean()).isFalse();
        assertThat(event.get("model").getAsString()).isEqualTo("model/a");
        assertThat(event.get("final_outcome").getAsString()).isEqualTo("ok");
        assertThat(event.get("final_http_status").getAsInt()).isEqualTo(200);
        assertThat(event.get("sample_reason").getAsString()).isEqualTo("random");
        assertThat(event.get("sample_rate").getAsDouble()).isEqualTo(1.0d);
        assertThat(event.get("configured_timeout_ms").getAsLong()).isEqualTo(120_000L);
        JsonObject attempt = event.getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertThat(attempt.get("host").getAsString()).isEqualTo("apex");
        assertThat(attempt.get("request_id").getAsString()).isEqualTo(REQUEST_ID);
        assertThat(attempt.get("http_status").getAsInt()).isEqualTo(200);
        assertThat(batch.getAsJsonArray("counters")).as("request + attempt rows").hasSize(2);
        assertThat(batch.getAsJsonObject("sdk").get("name").getAsString()).isEqualTo("tr-java");
        assertThat(batch.getAsJsonObject("sdk").get("version").getAsString())
                .isEqualTo(TrustedRouter.VERSION);
    }

    @Test void streamFirstItemRecordsTtft() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\n"
                + "data: {\"id\":\"c2\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        TrustedRouterClient client = client(wire, 0, 1.0d);

        try (EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                ChatRequest.builder().message("user", "hi").build())) {
            assertThat(stream.read().getId()).isEqualTo("c1");
            assertThat(stream.read().getId()).isEqualTo("c2");
            assertThat(stream.read()).isNull();
        }

        TelemetryReporter reporter = client.transport().telemetryReporter();
        JsonObject event = reporter.bufferedEvents().get(0);
        assertThat(event.get("streaming").getAsBoolean()).isTrue();
        assertThat(event.get("final_outcome").getAsString()).isEqualTo("ok");
        assertThat(event.get("ttft_ms").isJsonNull()).isFalse();
        assertThat(event.get("ttft_ms").getAsLong()).isBetween(0L, 60_000L);
        Map<CounterKey, CounterIncrement> counters = reporter.currentCounters();
        CounterKey request = new CounterKey("request", "chat_completions", true, "apex", "ok",
                null, "2xx", "none", false, false);
        assertThat(counters.get(request).firstEventMsHist.values()).containsExactly(1L);
        assertThat(counters.get(request).firstAttemptSuccess).isEqualTo(1L);
    }

    @Test void aStreamEndingBeforeDoneIsStreamBroken() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\n"));
        TrustedRouterClient client = client(wire, 2, 0.0d);

        EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                ChatRequest.builder().message("user", "hi").build());
        assertThat(stream.read().getId()).isEqualTo("c1");
        assertThatThrownBy(stream::read).isInstanceOf(InternalException.class)
                .hasMessageContaining("before [DONE]");
        stream.close();

        List<JsonObject> events = client.transport().telemetryReporter().bufferedEvents();
        assertThat(events).as("one record, never a reconnect").hasSize(1);
        JsonObject event = events.get(0);
        assertThat(event.get("final_outcome").getAsString()).isEqualTo("stream_broken");
        assertThat(event.get("sample_reason").getAsString()).isEqualTo("failure");
        assertThat(event.get("ttft_ms").isJsonNull()).isFalse();
        JsonObject attempt = event.getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertThat(attempt.get("outcome").getAsString()).isEqualTo("stream_broken");
        assertThat(attempt.get("error_class").getAsString()).isEqualTo("protocol_error");
        assertThat(attempt.get("http_status").getAsInt()).isEqualTo(200);
        assertThat(wire.paths).as("the engine never retried the broken stream").hasSize(1);
    }

    @Test void aSocketCutMidBodyIsStreamBroken() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        StringBuilder body = new StringBuilder("data: {\"id\":\"c1\",\"choices\":[]}\n\n");
        for (int index = 0; index < 200; index++) {
            body.append(": padding padding padding padding padding padding padding\n");
        }
        body.append("\ndata: [DONE]\n\n");
        engine.enqueue(sse(body.toString())
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        TrustedRouterClient client = client(wire, 2, 0.0d);

        EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                ChatRequest.builder().message("user", "hi").build());
        assertThat(stream.read().getId()).isEqualTo("c1");
        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
        stream.close();

        JsonObject event = client.transport().telemetryReporter().bufferedEvents().get(0);
        assertThat(event.get("final_outcome").getAsString()).isEqualTo("stream_broken");
        JsonObject attempt = event.getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertThat(attempt.get("error_class").getAsString())
                .isIn("protocol_error", "io_error", "reset");
        assertThat(attempt.get("http_status").getAsInt()).isEqualTo(200);
    }

    @Test void closingAStreamEarlyRecordsAbortedOnce() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\n"
                + "data: {\"id\":\"c2\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        TrustedRouterClient client = client(wire, 0, 0.0d);

        EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                ChatRequest.builder().message("user", "hi").build());
        assertThat(stream.read().getId()).isEqualTo("c1");
        stream.close();
        stream.close();

        List<JsonObject> events = client.transport().telemetryReporter().bufferedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("final_outcome").getAsString()).isEqualTo("aborted");
        assertThat(events.get(0).get("ttft_ms").isJsonNull()).isFalse();
        Map<CounterKey, CounterIncrement> counters =
                client.transport().telemetryReporter().currentCounters();
        assertThat(counters.keySet()).containsExactly(
                new CounterKey("request", "chat_completions", true, "apex", "aborted", null,
                        "2xx", "none", false, false),
                new CounterKey("attempt", "chat_completions", true, "apex", "aborted", null,
                        "2xx", "none", false, false));
    }

    @Test void aRawStreamRecordsItsFirstByteCompletionAndAbort() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        engine.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        TrustedRouterClient client = client(wire, 0, 1.0d);

        byte[] buffer = new byte[4096];
        try (InputStream stream = client.chatCompletionsRawStream(
                ChatRequest.builder().message("user", "hi").build())) {
            while (stream.read(buffer) >= 0) {
                // drain
            }
        }
        try (InputStream stream = client.chatCompletionsRawStream(
                ChatRequest.builder().message("user", "hi").build())) {
            assertThat(stream.read()).isGreaterThanOrEqualTo(0);
        }

        List<JsonObject> events = client.transport().telemetryReporter().bufferedEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("final_outcome").getAsString()).isEqualTo("ok");
        assertThat(events.get(0).get("ttft_ms").isJsonNull()).isFalse();
        assertThat(events.get(1).get("final_outcome").getAsString()).isEqualTo("aborted");
    }

    @Test void failuresAreAlwaysRecordedAndExhaustedWhenTheBudgetIsSpent() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        TrustedRouterClient client = client(wire, 1, 0.0d);

        assertThatThrownBy(() -> client.chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(InternalException.class);

        assertThat(wire.hosts).containsExactly("api.trustedrouter.com", "api.allyrouter.com");
        TelemetryReporter reporter = client.transport().telemetryReporter();
        JsonObject event = reporter.bufferedEvents().get(0);
        assertThat(event.get("final_outcome").getAsString()).isEqualTo("exhausted");
        assertThat(event.get("final_http_status").getAsInt()).isEqualTo(503);
        assertThat(event.get("sample_reason").getAsString()).isEqualTo("failure");
        assertThat(event.get("failover_used").getAsBoolean()).isTrue();
        assertThat(event.getAsJsonArray("attempts")).hasSize(2);
        assertThat(event.getAsJsonArray("attempts").get(0).getAsJsonObject()
                .get("moved").getAsBoolean()).isTrue();
        Map<CounterKey, CounterIncrement> counters = reporter.currentCounters();
        CounterIncrement request = counters.get(new CounterKey("request", "chat_completions",
                false, "ally", "http_error", null, "5xx", "none", false, false));
        assertThat(request.requests).isEqualTo(1L);
        assertThat(request.attempts).isEqualTo(2L);
        assertThat(request.failoverUsed).isEqualTo(1L);
        assertThat(request.firstAttemptSuccess).isZero();
    }

    @Test void successesAreSampledByTheConfiguredRate() throws Exception {
        WireRecorder quiet = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient unsampled = client(quiet, 0, 0.0d);
        unsampled.chatCompletions(ChatRequest.builder().message("user", "hi").build());
        TelemetryReporter reporter = unsampled.transport().telemetryReporter();
        assertThat(reporter.bufferedEvents()).as("a healthy success at rate 0").isEmpty();
        assertThat(reporter.currentCounters()).as("counters are exact, never sampled").hasSize(2);

        WireRecorder loud = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient sampled = client(loud, 0, 1.0d);
        sampled.chatCompletions(ChatRequest.builder().message("user", "hi").build());
        JsonObject event = sampled.transport().telemetryReporter().bufferedEvents().get(0);
        assertThat(event.get("sample_reason").getAsString()).isEqualTo("random");
    }

    @Test void countersAreExactForAScriptedSequence() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        engine.enqueue(json(200, completion("ok")));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = client(wire, 2, 0.0d);
        ChatRequest request = ChatRequest.builder().message("user", "hi").build();

        client.chatCompletions(request);
        client.chatCompletions(request);
        client.chatCompletions(request);

        Map<CounterKey, CounterIncrement> counters =
                client.transport().telemetryReporter().currentCounters();
        CounterKey requestApex = key("request", "apex", "ok", "2xx");
        CounterKey attemptApexOk = key("attempt", "apex", "ok", "2xx");
        CounterKey requestAlly = key("request", "ally", "ok", "2xx");
        CounterKey attemptApexError = key("attempt", "apex", "http_error", "5xx");
        CounterKey attemptAllyOk = key("attempt", "ally", "ok", "2xx");
        assertThat(counters.keySet()).containsExactly(
                requestApex, attemptApexOk, requestAlly, attemptApexError, attemptAllyOk);
        assertCounts(counters.get(requestApex), 2L, 2L, 0L, 2L);
        assertCounts(counters.get(attemptApexOk), 2L, 2L, 0L, 0L);
        assertCounts(counters.get(requestAlly), 1L, 2L, 1L, 0L);
        assertCounts(counters.get(attemptApexError), 1L, 1L, 1L, 0L);
        assertCounts(counters.get(attemptAllyOk), 1L, 1L, 0L, 0L);
        assertThat(sum(counters.get(requestApex).totalMsHist)).isEqualTo(2L);
        assertThat(sum(counters.get(requestApex).firstEventMsHist)).isEqualTo(2L);
        assertThat(sum(counters.get(requestAlly).totalMsHist)).isEqualTo(1L);
        assertThat(counters.get(attemptApexOk).totalMsHist).isEmpty();
    }

    @Test void aCustomBaseUrlByDefaultCreatesNoReporterAndSendsNoBeacon() throws Exception {
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .baseUrl(engine.url("/v1").toString())
                .controlBaseUrl(beacon.url("/v1").toString())
                .maxRetries(0)
                .build());
        clients.add(client);

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());
        client.close();

        assertThat(client.transport().telemetryReporter()).isNull();
        assertThat(control.requests).isEmpty();
    }

    @Test void controlPlaneCallsCreateNoReporterAndNoBeacon() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, "{\"data\":[]}"));
        TrustedRouterClient client = client(wire, 0, 1.0d);

        client.models();
        client.close();

        assertThat(wire.hosts).containsExactly(beacon.getHostName());
        assertThat(client.transport().telemetryReporter()).isNull();
        assertThat(control.requests).isEmpty();
    }

    @Test void anOptOutCreatesNoReporterAndNoWorker() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(wire).build())
                .controlBaseUrl(beacon.url("/v1").toString())
                .telemetry(Boolean.FALSE)
                .telemetrySampleRate(1.0d)
                .maxRetries(0)
                .build());
        clients.add(client);

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());
        client.close();

        assertThat(client.transport().telemetryReporter()).isNull();
        assertThat(wire.clientHeaders()).as("opt-out disables the header too")
                .containsExactly((String) null);
        assertThat(control.requests).isEmpty();
    }

    @Test void theWorkerIsADaemonStartedOnTheFirstRecordedCall() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = client(wire, 0, 0.0d);
        assertThat(client.transport().telemetryReporter()).as("never at construction").isNull();

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());
        TelemetryReporter reporter = client.transport().telemetryReporter();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (reporter.workerThread() == null && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(reporter.workerThread()).isNotNull();
        assertThat(reporter.workerThread().isDaemon()).isTrue();
        assertThat(reporter.workerThread().getName()).isEqualTo("trustedrouter-telemetry");
    }

    @Test void closeDeliversThePendingBatchOnce() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        TrustedRouterClient client = client(wire, 0, 0.0d);
        assertThatThrownBy(() -> client.chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(InternalException.class);

        long started = System.nanoTime();
        client.close();
        client.close();
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(2_500L);
        assertThat(control.requests).hasSize(1);
        JsonObject batch = control.batches.get(0);
        assertThat(batch.getAsJsonArray("events")).hasSize(1);
        assertThat(batch.getAsJsonArray("events").get(0).getAsJsonObject()
                .get("final_outcome").getAsString()).isEqualTo("http_error");
        assertThat(batch.getAsJsonArray("counters")).hasSize(2);
        assertThat(client.transport().telemetryReporter().isClosed()).isTrue();
    }

    @Test void closeIsBoundedByTwoSecondsWhenTheControlPlaneStalls() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        control.stall = true;
        TrustedRouterClient client = client(wire, 0, 0.0d);
        assertThatThrownBy(() -> client.chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(InternalException.class);

        long started = System.nanoTime();
        client.close();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(2_500L);
        assertThat(control.requests).as("exactly one attempt, never a retry").hasSize(1);
    }

    @Test void aRejectedBeaconIsNeverRetriedAndBacksOff() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        control.scripted.add(new MockResponse().setResponseCode(503));
        TrustedRouterClient client = client(wire, 0, 0.0d);
        assertThatThrownBy(() -> client.chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(InternalException.class);

        TelemetryReporter reporter = client.transport().telemetryReporter();
        assertThat(reporter.flushNow()).isFalse();
        assertThat(reporter.flushNow()).as("backing off: no second POST").isFalse();
        assertThat(control.requests).hasSize(1);
        assertThat(reporter.bufferedEvents()).as("nothing lost").hasSize(1);
    }

    @Test void promptTextAndCustomHostnamesNeverAppearInABatch() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = client(wire, 0, 1.0d);
        String prompt = "SECRET-PROMPT-7f3a9c the quick brown fox jumps over the lazy dog";

        client.chatCompletions(ChatRequest.builder()
                .model("a model name with spaces and " + prompt)
                .message("user", prompt)
                .build());
        assertThat(client.transport().telemetryReporter().flushNow()).isTrue();

        String body = control.bodies.get(0);
        assertThat(body).doesNotContain(prompt).doesNotContain("SECRET-PROMPT");
        assertThat(body).doesNotContain(engine.getHostName()).doesNotContain("localhost");
        assertThat(body).doesNotContain(":" + engine.getPort()).doesNotContain("127.0.0.1");
        assertThat(body).doesNotContain("/v1/chat/completions").doesNotContain("sk-test");
        assertThat(body).doesNotContain("Idempotency").doesNotContain("tr-req-");
        JsonObject batch = control.batches.get(0);
        assertSchemaKeysOnly(batch);
        JsonObject event = batch.getAsJsonArray("events").get(0).getAsJsonObject();
        assertThat(event.get("model").isJsonNull()).isTrue();
        assertThat(event.getAsJsonArray("attempts").get(0).getAsJsonObject()
                .get("host").getAsString()).isEqualTo("apex");
    }

    @Test void asyncCancellationRecordsAborted() throws Exception {
        WireRecorder wire = new WireRecorder(engine.url("/"));
        engine.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        TrustedRouterClient client = client(wire, 0, 0.0d);

        CompletableFuture<?> future = client.async().chatCompletions(
                ChatRequest.builder().message("user", "hi").build());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (wire.hosts.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        Thread.sleep(100L);
        assertThat(future.cancel(true)).isTrue();

        TelemetryReporter reporter = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            reporter = client.transport().telemetryReporter();
            if (reporter != null && !reporter.bufferedEvents().isEmpty()) {
                break;
            }
            Thread.sleep(10L);
        }
        assertThat(reporter).isNotNull();
        List<JsonObject> events = reporter.bufferedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("final_outcome").getAsString()).isEqualTo("aborted");
        assertThat(events.get(0).getAsJsonArray("attempts").get(0).getAsJsonObject()
                .get("http_status").isJsonNull()).isTrue();
    }

    @Test void theSampleRateOptionIsValidated() {
        assertThatThrownBy(() -> TrustedRouterOptions.builder().telemetrySampleRate(1.5d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedRouterOptions.builder().telemetrySampleRate(-0.1d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedRouterOptions.builder().telemetrySampleRate(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TrustedRouterOptions.builder().build().getTelemetrySampleRate())
                .isEqualTo(0.01d);
        assertThat(TrustedRouterOptions.builder().telemetrySampleRate(0.0d).build()
                .getTelemetrySampleRate()).isZero();
    }

    // --- helpers -------------------------------------------------------------

    private TrustedRouterClient client(WireRecorder wire, int retries, double sampleRate) {
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .workspaceId("ws_test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(wire).build())
                .controlBaseUrl(beacon.url("/v1").toString())
                .telemetry(Boolean.TRUE)
                .telemetrySampleRate(sampleRate)
                .maxRetries(retries)
                .build());
        clients.add(client);
        return client;
    }

    private static CounterKey key(String level, String host, String outcome, String statusClass) {
        return new CounterKey(level, "chat_completions", false, host, outcome, null, statusClass,
                "none", false, false);
    }

    private static void assertCounts(
            CounterIncrement increment, long requests, long attempts, long failoverUsed,
            long firstAttemptSuccess) {
        assertThat(increment).isNotNull();
        assertThat(increment.requests).isEqualTo(requests);
        assertThat(increment.attempts).isEqualTo(attempts);
        assertThat(increment.failoverUsed).isEqualTo(failoverUsed);
        assertThat(increment.firstAttemptSuccess).isEqualTo(firstAttemptSuccess);
    }

    private static long sum(Map<String, Long> histogram) {
        long total = 0L;
        for (Long value : histogram.values()) {
            total += value.longValue();
        }
        return total;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static String completion(String text) {
        return "{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"model\":\"test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    /** Every key in a serialised batch must belong to the schema (§5, §2.1). */
    private static void assertSchemaKeysOnly(JsonObject batch) {
        Set<String> batchKeys = new HashSet<String>(Arrays.asList("schema_version", "batch_id",
                "instance_id", "seq", "sent_at_ms", "sdk", "synthetic", "dropped_since_last",
                "events", "counters"));
        Set<String> eventKeys = new HashSet<String>(Arrays.asList("age_ms", "plane", "endpoint",
                "method", "streaming", "provider_pinned", "model", "attempts", "final_outcome",
                "final_http_status", "total_ms", "ttft_ms", "failover_used", "timeout_phase",
                "configured_timeout_ms", "sample_rate", "sample_reason"));
        Set<String> attemptKeys = new HashSet<String>(Arrays.asList("index", "host", "outcome",
                "http_status", "error_class", "error_source", "should_retry", "retry_after_ms",
                "elapsed_ms", "ttfb_ms", "request_id", "moved"));
        Set<String> counterKeys = new HashSet<String>(Arrays.asList("window_start_age_ms",
                "level", "endpoint", "streaming", "host", "outcome", "error_class",
                "http_status_class", "timeout_phase", "timeout_floor_met", "provider_pinned",
                "requests", "attempts", "failover_used", "first_attempt_success",
                "total_ms_hist", "first_event_ms_hist"));
        assertThat(batch.keySet()).isSubsetOf(batchKeys);
        for (JsonElement element : batch.getAsJsonArray("events")) {
            assertThat(element.getAsJsonObject().keySet()).isSubsetOf(eventKeys);
            for (JsonElement attempt : element.getAsJsonObject().getAsJsonArray("attempts")) {
                assertThat(attempt.getAsJsonObject().keySet()).isSubsetOf(attemptKeys);
            }
        }
        for (JsonElement element : batch.getAsJsonArray("counters")) {
            assertThat(element.getAsJsonObject().keySet()).isSubsetOf(counterKeys);
        }
    }

    /** The fake control plane: records every beacon and answers 202 unless scripted. */
    private static final class BeaconDispatcher extends Dispatcher {
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<RecordedRequest>();
        private final List<String> bodies = new CopyOnWriteArrayList<String>();
        private final List<JsonObject> batches = new CopyOnWriteArrayList<JsonObject>();
        private final List<MockResponse> scripted = new CopyOnWriteArrayList<MockResponse>();
        private volatile boolean stall;

        @Override public MockResponse dispatch(RecordedRequest request) {
            requests.add(request);
            String body = request.getBody().readUtf8();
            bodies.add(body);
            batches.add(JsonSupport.parse(body).getAsJsonObject());
            if (stall) {
                return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
            }
            if (!scripted.isEmpty()) {
                return scripted.remove(0);
            }
            return new MockResponse().setResponseCode(202)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"accepted_events\":0,\"accepted_counters\":0,"
                            + "\"dropped\":0},\"policy\":{}}");
        }
    }

    /**
     * Records the hosts, paths, and x-tr-client headers the ENGINE sent, then
     * reroutes to the local inference server — so the real TrustedRouter
     * hostnames (and therefore the host enum) are exercised without DNS.
     */
    private static final class WireRecorder implements Interceptor {
        private final HttpUrl target;
        private final List<String> hosts = new CopyOnWriteArrayList<String>();
        private final List<String> paths = new CopyOnWriteArrayList<String>();
        private final List<String> clientHeaders = new CopyOnWriteArrayList<String>();

        WireRecorder(HttpUrl target) {
            this.target = target;
        }

        @Override public Response intercept(Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            hosts.add(request.url().host());
            paths.add(request.url().encodedPath());
            String header = request.header("x-tr-client");
            clientHeaders.add(header == null ? "" : header);
            return chain.proceed(request.newBuilder()
                    .url(request.url().newBuilder()
                            .scheme(target.scheme())
                            .host(target.host())
                            .port(target.port())
                            .build())
                    .build());
        }

        List<String> clientHeaders() {
            List<String> values = new ArrayList<String>();
            for (String header : clientHeaders) {
                values.add(header.isEmpty() ? null : header);
            }
            return Collections.unmodifiableList(values);
        }
    }
}
