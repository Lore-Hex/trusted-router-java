package com.trustedrouter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.internal.RequestRecorder.AttemptRecord;
import com.trustedrouter.internal.RequestRecorder.CounterIncrement;
import com.trustedrouter.internal.RequestRecorder.CounterKey;
import com.trustedrouter.internal.RequestRecorder.CounterUpdate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The beacon reporter (client telemetry contract v1, &sect;4/&sect;5/&sect;6.2),
 * scenario for scenario with the Python reference's
 * {@code test_telemetry_reporter.py}: sampling, the 1 000-event bound, the
 * 256-key fold ladder, 24 h / byte-capped retention across a failed flush,
 * the bounded content-free wire, policy that only reduces volume, the
 * disable statuses, {@code x-tr-telemetry: off}, Retry-After backoff, the
 * 65 536-byte trim, the backlog drain, and the bounded lifecycle.
 */
final class TelemetryReporterTest {
    private static final String REQUEST_ID = "rlog_0123456789abcdef0123456789abcdef";
    private static final JsonObject SDK = identity();

    private final List<TelemetryReporter> reporters = new ArrayList<TelemetryReporter>();

    @AfterEach void closeReporters() {
        for (TelemetryReporter reporter : reporters) {
            reporter.close(100L);
        }
    }

    // --- sampling (§5.3) ---------------------------------------------------

    @Test void samplingKeepsFailuresRetriesSlowCallsAndSampledRandomSuccesses() {
        TelemetryReporter reporter = reporter(new FakeSender(), new FakeClock(), 0.0d, null);
        reporter.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                "model/a", false), noCounters());
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200), attempt(1, "ok", 200)),
                25L, "model/a", false), noCounters());
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 30_001L, "model/a",
                false), noCounters());
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "model/a", true),
                noCounters());
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "model/a", false),
                noCounters());

        List<JsonObject> events = reporter.bufferedEvents();
        assertThat(reasons(events)).containsExactly("failure", "retried", "slow", "retried");
        for (JsonObject event : events) {
            assertThat(event.get("sample_rate").getAsDouble()).isEqualTo(1.0d);
        }

        TelemetryReporter sampled = reporter(new FakeSender(), new FakeClock(), 1.0d, null);
        sampled.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "model/a", false),
                noCounters());
        assertThat(sampled.bufferedEvents().get(0).get("sample_reason").getAsString())
                .isEqualTo("random");
        assertThat(sampled.bufferedEvents().get(0).get("sample_rate").getAsDouble())
                .isEqualTo(1.0d);
    }

    @Test void randomSamplingIsDeterministicUnderAnInjectedSource() {
        // draw < rate keeps the event; the scripted source makes the outcome exact.
        TelemetryReporter reporter = reporter(new FakeSender(), new FakeClock(), 0.5d,
                new ScriptedRandom(0.2d, 0.9d, 0.49d, 0.5d));
        for (int index = 0; index < 4; index++) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L,
                    "ok-" + index, false), noCounters());
        }
        List<JsonObject> events = reporter.bufferedEvents();
        assertThat(models(events)).containsExactly("ok-0", "ok-2");
        assertThat(events.get(0).get("sample_reason").getAsString()).isEqualTo("random");
        assertThat(events.get(0).get("sample_rate").getAsDouble()).isEqualTo(0.5d);
    }

    // --- bounds (§6.2) -------------------------------------------------------

    @Test void boundedEventsDropOldestSuccessBeforeOldestFailure() {
        TelemetryReporter reporter = reporter(new FakeSender(), new FakeClock(), 1.0d, null);
        reporter.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                "failure", false), noCounters());
        for (int index = 0; index < 999; index++) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L,
                    "ok-" + index, false), noCounters());
        }
        reporter.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                "new-failure", false), noCounters());

        List<JsonObject> events = reporter.bufferedEvents();
        assertThat(events).hasSize(Telemetry.MAX_EVENTS);
        assertThat(events.get(0).get("model").getAsString()).isEqualTo("failure");
        assertThat(models(events)).doesNotContain("ok-0").contains("ok-1", "new-failure");
        assertThat(reporter.droppedSinceLast()).isEqualTo(1L);

        TelemetryReporter failures = reporter(new FakeSender(), new FakeClock(), 0.0d, null);
        for (int index = 0; index < 1001; index++) {
            failures.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)),
                    25L, "failure-" + index, false), noCounters());
        }
        assertThat(failures.bufferedEvents().get(0).get("model").getAsString())
                .isEqualTo("failure-1");
        assertThat(failures.droppedSinceLast()).isEqualTo(1L);
    }

    @Test void countersFoldAt256KeysAndCloseWhenTheMinuteChanges() {
        FakeClock clock = new FakeClock();
        clock.nowMs = 1_000L;
        TelemetryReporter reporter = reporter(new FakeSender(), clock, 0.0d, null);
        List<CounterKey> keys = product(Telemetry.ENDPOINTS, Telemetry.ERROR_CLASSES,
                Telemetry.HTTP_STATUS_CLASSES, "request");
        for (CounterKey key : keys.subList(0, 257)) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                    updates(key));
        }
        Map<CounterKey, CounterIncrement> current = reporter.currentCounters();
        assertThat(current).hasSize(Telemetry.MAX_WINDOW_KEYS);
        assertThat(totalRequests(current)).isEqualTo(257L);
        // The 257th key is (embeddings, reset, 2xx). Its error-class fold is
        // absent, so the first existing key that matches it except for error
        // class — (embeddings, dns, 2xx) — is re-keyed to (embeddings,
        // unknown, 2xx), merged, and takes the new increment: requests 2.
        CounterKey folded = key("request", "embeddings", "apex", "ok", "unknown", "2xx");
        assertThat(current.get(folded).requests).isEqualTo(2L);
        assertThat(current).doesNotContainKey(key("request", "embeddings", "apex", "ok", "dns", "2xx"));
        assertThat(current).doesNotContainKey(keys.get(256));
        assertThat(lastKey(current)).isEqualTo(folded);
        assertThat(reporter.droppedSinceLast()).as("folding never counts as a drop").isZero();

        clock.advance(60_000L);
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                updates(key("request", "responses", "apex", "ok", null, "2xx")));
        assertThat(reporter.closedWindowStartsMs()).containsExactly(0L);
        assertThat(reporter.currentWindowStartMs()).isEqualTo(60_000L);
        assertThat(reporter.currentCounters()).hasSize(1);
    }

    @Test void theSecondFoldRungCoarsensTheEndpoint() {
        TelemetryReporter reporter = reporter(new FakeSender(), new FakeClock(), 0.0d, null);
        List<String> endpoints = new ArrayList<String>(Telemetry.ENDPOINTS);
        endpoints.remove("fusion");
        endpoints.remove("inference_other");
        List<CounterKey> keys = product(endpoints, Telemetry.ERROR_CLASSES,
                Telemetry.HTTP_STATUS_CLASSES, "request");
        for (CounterKey key : keys.subList(0, 256)) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                    updates(key));
        }
        // No existing fusion key shares the new key's other fields, so the
        // error rung misses; the endpoint rung re-keys the first key that
        // matches except endpoint and error class — (chat_completions, dns,
        // 2xx) — to (inference_other, unknown, 2xx).
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                updates(key("request", "fusion", "apex", "ok", "tls", "2xx")));
        Map<CounterKey, CounterIncrement> current = reporter.currentCounters();
        CounterKey folded = key("request", "inference_other", "apex", "ok", "unknown", "2xx");
        assertThat(current).hasSize(256);
        assertThat(totalRequests(current)).isEqualTo(257L);
        assertThat(current.get(folded).requests).isEqualTo(2L);
        assertThat(current).doesNotContainKey(
                key("request", "chat_completions", "apex", "ok", "dns", "2xx"));
    }

    @Test void theFinalFoldRungMergesIntoAnyExistingKey() {
        TelemetryReporter reporter = reporter(new FakeSender(), new FakeClock(), 0.0d, null);
        List<CounterKey> keys = new ArrayList<CounterKey>();
        for (String host : Telemetry.HOSTS) {
            for (String status : Telemetry.HTTP_STATUS_CLASSES) {
                for (String errorClass : Telemetry.ERROR_CLASSES) {
                    keys.add(new CounterKey("attempt", "chat_completions", false, host, "ok",
                            errorClass, status, "none", false, false));
                }
            }
        }
        for (CounterKey key : keys.subList(0, 256)) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                    updates(key));
        }
        // A request-level key matches nothing on either rung (level differs
        // everywhere), so it lands on the first existing key: counts exact.
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                updates(key("request", "chat_completions", "apex", "ok", null, "2xx")));
        Map<CounterKey, CounterIncrement> current = reporter.currentCounters();
        assertThat(current).hasSize(256);
        assertThat(totalRequests(current)).isEqualTo(257L);
        assertThat(current.get(keys.get(0)).requests).isEqualTo(2L);
        assertThat(reporter.droppedSinceLast()).isZero();
    }

    @Test void failedFlushRetainsCountersThenDeliversThemWithTheirAge() {
        FakeClock clock = new FakeClock();
        FakeSender sender = new FakeSender();
        sender.script.add(new TelemetryReporter.SendResult(503, null, null, "{\"policy\":{}}"));
        sender.script.add(accepted());
        TelemetryReporter reporter = reporter(sender, clock, 0.0d, null);
        record(reporter);

        assertThat(reporter.flushNow()).isFalse();
        assertThat(reporter.closedWindowStartsMs()).hasSize(1);

        clock.advance(120_000L);
        assertThat(reporter.flushNow()).isTrue();
        assertThat(sender.batches).hasSize(2);
        JsonObject counter = sender.batches.get(1).getAsJsonArray("counters").get(0)
                .getAsJsonObject();
        assertThat(counter.get("window_start_age_ms").getAsLong()).isEqualTo(120_000L);
        assertThat(reporter.closedWindowStartsMs()).isEmpty();
    }

    @Test void retentionDropsExpiredAndByteCappedWindowsOldestFirst() {
        FakeClock clock = new FakeClock();
        TelemetryReporter reporter = builder(new FakeSender(), clock, 0.0d, null)
                .retentionBytes(700L).build();
        reporters.add(reporter);
        for (int minute = 0; minute < 4; minute++) {
            record(reporter);
            clock.advance(60_000L);
        }
        reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                noCounters());

        List<Long> starts = reporter.closedWindowStartsMs();
        List<Long> sorted = new ArrayList<Long>(starts);
        Collections.sort(sorted);
        assertThat(starts).isEqualTo(sorted).isNotEmpty();
        assertThat(starts.get(0)).as("oldest windows go first").isGreaterThan(0L);
        assertThat(reporter.retainedWindowBytes()).isLessThanOrEqualTo(700L);
        assertThat(reporter.droppedSinceLast()).isGreaterThan(0L);

        clock.advance(86_401_000L);
        reporter.pruneWindowsNow();
        assertThat(reporter.closedWindowStartsMs()).isEmpty();
    }

    // --- the wire (§5.1, §2.1) ---------------------------------------------

    @Test void wireIsBoundedContentFreeAndCarriesOnlySchemaKeys() {
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = builder(sender, new FakeClock(), 1.0d, null)
                .workspaceId("ws_test").build();
        reporters.add(reporter);
        String injected = "private prompt text that must not leave";
        AttemptRecord attempt = attempt(0, "ok", 200);
        attempt.shouldRetry = "true";
        attempt.requestId = REQUEST_ID;
        reporter.onRequest(event("ok", attempts(attempt), 25L, injected, false),
                updates(key("request", "responses", "apex", "ok", null, "2xx")));

        assertThat(reporter.flushNow()).isTrue();
        assertThat(sender.urls).containsExactly("https://trustedrouter.com/v1/client-events");
        Map<String, String> headers = sender.headers.get(0);
        assertThat(headers.get("Authorization")).isEqualTo("Bearer sk-tr-test");
        assertThat(headers.get("X-TrustedRouter-Workspace")).isEqualTo("ws_test");
        assertThat(headers.get("Content-Type")).isEqualTo("application/json");
        assertThat(headers.get("User-Agent")).startsWith("trusted-router-java/");
        assertThat(headers).doesNotContainKey("x-tr-client");

        JsonObject body = sender.batches.get(0);
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "schema_version", "batch_id", "instance_id", "seq", "sent_at_ms", "sdk",
                "synthetic", "dropped_since_last", "events", "counters");
        assertThat(body.get("schema_version").getAsInt()).isEqualTo(1);
        assertThat(body.get("batch_id").getAsString()).matches("^[0-9a-f]{32}$");
        assertThat(body.get("instance_id").getAsString()).matches("^[0-9a-f]{16}$");
        assertThat(body.get("instance_id").getAsString()).isEqualTo(reporter.instanceId());
        assertThat(body.get("seq").getAsLong()).isZero();
        assertThat(body.get("synthetic").getAsBoolean()).isFalse();
        assertThat(body.getAsJsonObject("sdk").keySet()).containsExactlyInAnyOrder(
                "name", "version", "lang", "runtime", "os", "arch");
        JsonObject event = body.getAsJsonArray("events").get(0).getAsJsonObject();
        assertThat(event.get("model").isJsonNull()).as("out-of-grammar model is null").isTrue();
        assertThat(event.getAsJsonArray("attempts").get(0).getAsJsonObject()
                .get("should_retry").getAsBoolean()).isTrue();
        assertThat(event.getAsJsonArray("attempts").get(0).getAsJsonObject()
                .get("request_id").getAsString()).isEqualTo(REQUEST_ID);
        String encoded = sender.bodies.get(0);
        assertThat(encoded).doesNotContain(injected);
        assertThat(event.keySet()).doesNotContain("messages", "prompt", "input", "content", "text");
        assertSchemaKeysOnly(body);
        assertThat(sender.bodies.get(0).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Telemetry.MAX_BATCH_BYTES);
    }

    @Test void anUnobservedShouldRetryIsAbsentNotNull() {
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = reporter(sender, new FakeClock(), 1.0d, null);
        record(reporter);
        assertThat(reporter.flushNow()).isTrue();
        JsonObject attempt = sender.batches.get(0).getAsJsonArray("events").get(0)
                .getAsJsonObject().getAsJsonArray("attempts").get(0).getAsJsonObject();
        assertThat(attempt.keySet()).doesNotContain("should_retry");
        assertThat(attempt.keySet()).containsExactlyInAnyOrder("index", "host", "outcome",
                "http_status", "error_class", "error_source", "retry_after_ms", "elapsed_ms",
                "ttfb_ms", "request_id", "moved");
    }

    @Test void aBatchIsTrimmedUntilItFitsAndTheRestFollows() {
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = reporter(sender, new FakeClock(), 0.0d, null);
        List<AttemptRecord> many = new ArrayList<AttemptRecord>();
        for (int index = 0; index < 16; index++) {
            AttemptRecord attempt = attempt(index, "http_error", 503);
            attempt.requestId = REQUEST_ID;
            attempt.shouldRetry = "true";
            attempt.retryAfterMs = Long.valueOf(1_000L);
            many.add(attempt);
        }
        for (int index = 0; index < 100; index++) {
            reporter.onRequest(event("http_error", many, 25L, "model/a", true), noCounters());
        }
        assertThat(reporter.bufferedEvents()).hasSize(100);

        assertThat(reporter.flushNow()).isTrue();
        int firstBytes = sender.bodies.get(0).getBytes(StandardCharsets.UTF_8).length;
        assertThat(firstBytes).isLessThanOrEqualTo(Telemetry.MAX_BATCH_BYTES);
        int firstCount = sender.batches.get(0).getAsJsonArray("events").size();
        assertThat(firstCount).isLessThan(100).isGreaterThan(0);
        assertThat(reporter.bufferedEvents()).hasSize(100 - firstCount);

        int delivered = firstCount;
        while (delivered < 100) {
            assertThat(reporter.flushNow()).isTrue();
            JsonObject batch = sender.batches.get(sender.batches.size() - 1);
            assertThat(sender.bodies.get(sender.bodies.size() - 1)
                    .getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(Telemetry.MAX_BATCH_BYTES);
            delivered += batch.getAsJsonArray("events").size();
        }
        assertThat(delivered).isEqualTo(100);
        assertThat(reporter.bufferedEvents()).isEmpty();
        assertThat(reporter.droppedSinceLast()).as("trimming is deferral, not a drop").isZero();
    }

    @Test void aBacklogDrainsInBatchesOfAtMost100EventsAnd200Counters() {
        FakeClock clock = new FakeClock();
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = reporter(sender, clock, 0.0d, null);
        for (int index = 0; index < 150; index++) {
            reporter.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false), noCounters());
        }
        List<CounterKey> keys = product(Telemetry.ENDPOINTS, Telemetry.ERROR_CLASSES,
                Telemetry.HTTP_STATUS_CLASSES, "attempt");
        for (CounterKey key : keys.subList(0, 250)) {
            reporter.onRequest(event("ok", attempts(attempt(0, "ok", 200)), 25L, "m", false),
                    updates(key));
        }
        int deliveredEvents = 0;
        int deliveredCounters = 0;
        int sequence = 0;
        while (reporter.flushNow()) {
            JsonObject batch = sender.batches.get(sequence);
            int eventCount = batch.getAsJsonArray("events").size();
            int counterCount = batch.getAsJsonArray("counters").size();
            assertThat(eventCount).isLessThanOrEqualTo(Telemetry.MAX_BATCH_EVENTS);
            assertThat(counterCount).isLessThanOrEqualTo(Telemetry.MAX_BATCH_COUNTERS);
            assertThat(eventCount + counterCount).as("every POST is non-empty").isPositive();
            assertThat(sender.bodies.get(sequence).getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(Telemetry.MAX_BATCH_BYTES);
            assertThat(batch.get("seq").getAsLong()).isEqualTo(sequence);
            deliveredEvents += eventCount;
            deliveredCounters += counterCount;
            sequence++;
            assertThat(sequence).as("the bounded backlog drains").isLessThan(10);
        }
        assertThat(deliveredEvents).isEqualTo(150);
        assertThat(deliveredCounters).isEqualTo(250);
        assertThat(sender.batches).hasSize(sequence);
    }

    @Test void droppedSinceLastIsReportedThenReconciledOnAcceptance() {
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = reporter(sender, new FakeClock(), 0.0d, null);
        for (int index = 0; index < 1_003; index++) {
            reporter.onRequest(event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false), noCounters());
        }
        assertThat(reporter.droppedSinceLast()).isEqualTo(3L);
        assertThat(reporter.flushNow()).isTrue();
        assertThat(sender.batches.get(0).get("dropped_since_last").getAsLong()).isEqualTo(3L);
        assertThat(reporter.droppedSinceLast()).isZero();
    }

    // --- responses (§4, §6.2) ----------------------------------------------

    @Test void policyOnlyReducesVolumeAndPauseDefersDelivery() {
        FakeClock clock = new FakeClock();
        FakeSender sender = new FakeSender();
        sender.script.add(new TelemetryReporter.SendResult(202, null, null,
                "{\"policy\":{\"success_sample_rate\":0.005,\"flush_seconds\":60,"
                        + "\"pause_seconds\":120}}"));
        sender.script.add(new TelemetryReporter.SendResult(202, null, null,
                "{\"policy\":{\"success_sample_rate\":0.5,\"flush_seconds\":1,"
                        + "\"pause_seconds\":0}}"));
        TelemetryReporter reporter = reporter(sender, clock, 0.01d, null);
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).isTrue();
        assertThat(reporter.successSampleRate()).isEqualTo(0.005d);
        assertThat(reporter.flushIntervalMs()).isEqualTo(60_000L);

        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).as("paused").isFalse();
        assertThat(sender.batches).hasSize(1);
        clock.advance(120_000L);
        assertThat(reporter.flushNow()).isTrue();
        assertThat(reporter.successSampleRate()).as("never raised").isEqualTo(0.005d);
        assertThat(reporter.flushIntervalMs()).as("never shortened").isEqualTo(60_000L);
    }

    @Test void anOverlongPauseAndNonNumericPolicyAreIgnored() {
        FakeSender sender = new FakeSender();
        sender.script.add(new TelemetryReporter.SendResult(202, null, null,
                "{\"policy\":{\"success_sample_rate\":\"zero\",\"flush_seconds\":null,"
                        + "\"pause_seconds\":86401}}"));
        TelemetryReporter reporter = reporter(sender, new FakeClock(), 0.01d, null);
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).isTrue();
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).as("no pause was applied").isTrue();
        assertThat(reporter.successSampleRate()).isEqualTo(0.01d);
        assertThat(reporter.flushIntervalMs()).isEqualTo(Telemetry.FLUSH_INTERVAL_MS);
    }

    @Test void permanentResponsesDisableAndClearTheReporter() {
        for (int status : new int[] {400, 401, 403, 404, 410}) {
            FakeSender sender = new FakeSender();
            sender.script.add(new TelemetryReporter.SendResult(status, null, null, ""));
            TelemetryReporter reporter = reporter(sender, new FakeClock(), 0.0d, null);
            record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false));
            assertThat(reporter.flushNow()).as("status %s", status).isFalse();
            assertThat(reporter.isDisabled()).as("status %s", status).isTrue();
            record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false));
            assertThat(reporter.flushNow()).isFalse();
            assertThat(sender.batches).as("never a second POST after %s", status).hasSize(1);
            assertThat(reporter.bufferedEvents()).isEmpty();
            assertThat(reporter.closedWindowStartsMs()).isEmpty();
            assertThat(reporter.currentCounters()).isEmpty();
        }
    }

    @Test void a413DropsTheBatchAndCountsEveryDroppedItem() {
        FakeSender sender = new FakeSender();
        sender.script.add(new TelemetryReporter.SendResult(413, null, null, ""));
        TelemetryReporter reporter = reporter(sender, new FakeClock(), 0.0d, null);
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).isFalse();
        assertThat(reporter.isDisabled()).isFalse();
        assertThat(reporter.bufferedEvents()).isEmpty();
        assertThat(reporter.closedWindowStartsMs()).isEmpty();
        assertThat(reporter.droppedSinceLast()).as("one event and one counter row").isEqualTo(2L);
    }

    @Test void offHeaderDisablesAndRetryAfterBacksOffWithoutLosingData() throws Exception {
        // The real single-shot sender against a local endpoint, so the
        // Retry-After and x-tr-telemetry headers are read off a real socket.
        MockWebServer beacon = new MockWebServer();
        beacon.start();
        try {
            FakeClock clock = new FakeClock();
            beacon.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "120"));
            beacon.enqueue(accepted202());
            TelemetryReporter reporter = builder(null, clock, 0.0d, null)
                    .controlBaseUrl(beacon.url("/v1").toString()).build();
            reporters.add(reporter);
            record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false));
            assertThat(reporter.flushNow()).isFalse();
            clock.advance(119_000L);
            assertThat(reporter.flushNow()).as("still inside Retry-After").isFalse();
            assertThat(beacon.getRequestCount()).isEqualTo(1);
            clock.advance(1_000L);
            assertThat(reporter.flushNow()).isTrue();
            assertThat(beacon.getRequestCount()).isEqualTo(2);
            RecordedRequest first = beacon.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest second = beacon.takeRequest(1, TimeUnit.SECONDS);
            assertThat(first.getPath()).isEqualTo("/v1/client-events");
            assertThat(first.getHeader("x-tr-client")).isNull();
            assertThat(first.getHeader("Authorization")).isEqualTo("Bearer sk-tr-test");
            JsonObject retried = JsonSupport.parse(second.getBody().readUtf8()).getAsJsonObject();
            assertThat(retried.getAsJsonArray("events")).as("nothing lost").hasSize(1);
            assertThat(retried.get("seq").getAsLong()).as("a new batch, never a retry").isEqualTo(1L);

            beacon.enqueue(accepted202().setHeader("x-tr-telemetry", "off"));
            TelemetryReporter off = builder(null, new FakeClock(), 0.0d, null)
                    .controlBaseUrl(beacon.url("/v1").toString()).build();
            reporters.add(off);
            record(off, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                    false));
            assertThat(off.flushNow()).isTrue();
            assertThat(off.isDisabled()).isTrue();
        } finally {
            beacon.shutdown();
        }
    }

    @Test void backoffGrowsExponentiallyFrom60sTo10minAndResetsOn202() {
        FakeClock clock = new FakeClock();
        FakeSender sender = new FakeSender();
        for (int index = 0; index < 6; index++) {
            sender.script.add(new TelemetryReporter.SendResult(503, null, null, ""));
        }
        sender.script.add(accepted());
        sender.script.add(new TelemetryReporter.SendResult(503, null, null, ""));
        TelemetryReporter reporter = reporter(sender, clock, 0.0d, null);
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        long[] expectedWaits = {60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L};
        for (long wait : expectedWaits) {
            assertThat(reporter.flushNow()).isFalse();
            clock.advance(wait - 1L);
            assertThat(reporter.flushNow()).as("blocked until %s ms", wait).isFalse();
            clock.advance(1L);
        }
        assertThat(reporter.flushNow()).as("202 after the ladder").isTrue();
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).isFalse();
        clock.advance(59_999L);
        assertThat(reporter.flushNow()).as("backoff reset to 60 s by the 202").isFalse();
        assertThat(sender.batches).hasSize(8);
    }

    @Test void retryAfterIsHonouredOnlyUpTo600Seconds() {
        assertThat(TelemetryReporter.retryAfterMs("0")).isEqualTo(0L);
        assertThat(TelemetryReporter.retryAfterMs(" 120 ")).isEqualTo(120_000L);
        assertThat(TelemetryReporter.retryAfterMs("600")).isEqualTo(600_000L);
        assertThat(TelemetryReporter.retryAfterMs("600.5")).isNull();
        assertThat(TelemetryReporter.retryAfterMs("-1")).isNull();
        assertThat(TelemetryReporter.retryAfterMs("NaN")).isNull();
        assertThat(TelemetryReporter.retryAfterMs("Infinity")).isNull();
        assertThat(TelemetryReporter.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT")).isNull();
        assertThat(TelemetryReporter.retryAfterMs(null)).isNull();
    }

    @Test void transportErrorBacksOffAndDebugEchoesTheExactBatch() {
        FakeClock clock = new FakeClock();
        FakeSender sender = new FakeSender();
        sender.script.add(new IOException("offline"));
        sender.script.add(accepted());
        TelemetryReporter reporter = builder(sender, clock, 0.0d, null).debug(Boolean.TRUE).build();
        reporters.add(reporter);
        record(reporter, event("transport_error", attempts(attempt(0, "transport_error", null)),
                25L, "m", false));

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            assertThat(reporter.flushNow()).isFalse();
        } finally {
            System.setErr(original);
        }
        String echo = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
        assertThat(echo).startsWith("trustedrouter telemetry batch: ");
        String echoed = echo.substring("trustedrouter telemetry batch: ".length());
        assertThat(echoed).as("the echo is the exact wire body").isEqualTo(sender.bodies.get(0));
        assertSchemaKeysOnly(JsonSupport.parse(echoed).getAsJsonObject());

        clock.advance(59_000L);
        assertThat(reporter.flushNow()).isFalse();
        clock.advance(1_000L);
        assertThat(reporter.flushNow()).isTrue();
        assertThat(sender.batches).hasSize(2);
    }

    @Test void debugFollowsOnlyTheDocumentedEnvironmentValue() {
        Map<String, String> environ = new HashMap<String, String>();
        assertThat(TelemetryReporter.debugFromEnvironment(environ)).isFalse();
        environ.put("TRUSTEDROUTER_TELEMETRY_DEBUG", "1");
        assertThat(TelemetryReporter.debugFromEnvironment(environ)).isTrue();
        environ.put("TRUSTEDROUTER_TELEMETRY_DEBUG", "true");
        assertThat(TelemetryReporter.debugFromEnvironment(environ)).isFalse();
        assertThat(TelemetryReporter.debugFromEnvironment(null)).isFalse();
    }

    @Test void noApiKeyMeansNoPostAndNoLoss() {
        FakeSender sender = new FakeSender();
        TelemetryReporter reporter = builder(sender, new FakeClock(), 0.0d, null)
                .apiKey(() -> null).build();
        reporters.add(reporter);
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.flushNow()).isFalse();
        assertThat(sender.batches).isEmpty();
        assertThat(reporter.bufferedEvents()).hasSize(1);
    }

    // --- lifecycle (§6.2) ----------------------------------------------------

    @Test void lifecycleIsLazyDaemonAndCloseIsBounded() throws Exception {
        SlowSender sender = new SlowSender();
        TelemetryReporter reporter = builder(sender, new FakeClock(), 0.0d, null)
                .automaticWorker(true).build();
        reporters.add(reporter);
        assertThat(reporter.workerThread()).as("no worker before the first record").isNull();
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (reporter.workerThread() == null && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        Thread worker = reporter.workerThread();
        assertThat(worker).isNotNull();
        assertThat(worker.isDaemon()).isTrue();
        assertThat(worker.getName()).isEqualTo("trustedrouter-telemetry");

        long started = System.nanoTime();
        reporter.close(500L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertThat(elapsedMs).as("close is bounded by its timeout").isLessThan(1_000L);
        assertThat(reporter.isClosed()).isTrue();
        reporter.close(500L);
        int pending = reporter.bufferedEvents().size();
        record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(reporter.bufferedEvents()).as("closed reporters record nothing new")
                .hasSize(pending);
        assertThat(sender.closed.await(6, TimeUnit.SECONDS)).as("own client released").isTrue();
        assertThat(reporter.bufferedEvents()).as("accepted final flush reconciles the buffer").isEmpty();
    }

    @Test void theWorkerFlushesOnItsOwnAtTheIntervalAndUrgentlyAt50Events() throws Exception {
        FakeSender sender = new FakeSender();
        TelemetryReporter interval = builder(sender, null, 0.0d, null)
                .automaticWorker(true).flushIntervalMs(200L).build();
        reporters.add(interval);
        record(interval, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                false));
        assertThat(sender.delivered.await(5, TimeUnit.SECONDS))
                .as("the worker flushed at the interval without flushNow").isTrue();
        assertThat(sender.batches.get(0).getAsJsonArray("events")).hasSize(1);

        FakeSender urgentSender = new FakeSender();
        TelemetryReporter urgent = builder(urgentSender, null, 0.0d, null)
                .automaticWorker(true).build();
        reporters.add(urgent);
        for (int index = 0; index < Telemetry.BATCH_TRIGGER_EVENTS; index++) {
            record(urgent, event("http_error", attempts(attempt(0, "http_error", 503)), 25L, "m",
                    false));
        }
        assertThat(urgentSender.delivered.await(5, TimeUnit.SECONDS))
                .as("50 buffered events flush without waiting 30 s").isTrue();
        assertThat(urgentSender.batches.get(0).getAsJsonArray("events")).hasSize(50);
    }

    @Test void closeDeliversThePendingBatchWithinTwoSecondsAgainstAStallingEndpoint()
            throws Exception {
        MockWebServer beacon = new MockWebServer();
        beacon.start();
        try {
            beacon.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            TelemetryReporter reporter = builder(null, null, 0.0d, null)
                    .controlBaseUrl(beacon.url("/v1").toString()).build();
            reporters.add(reporter);
            record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false));
            long started = System.nanoTime();
            reporter.close(Telemetry.FINAL_FLUSH_MS);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).isLessThan(2_500L);
            RecordedRequest attempt = beacon.takeRequest(1, TimeUnit.SECONDS);
            assertThat(attempt).as("the final flush was attempted exactly once").isNotNull();
            assertThat(attempt.getPath()).isEqualTo("/v1/client-events");
        } finally {
            beacon.shutdown();
        }
    }

    @Test void ownSenderMakesOnePhysicalPostForA503WithImmediateRetryAfter() throws Exception {
        MockWebServer beacon = new MockWebServer();
        beacon.start();
        try {
            beacon.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            beacon.enqueue(accepted202());
            TelemetryReporter reporter = builder(null, new FakeClock(), 0.0d, null)
                    .controlBaseUrl(beacon.url("/v1").toString()).build();
            reporters.add(reporter);
            record(reporter, event("http_error", attempts(attempt(0, "http_error", 503)), 25L,
                    "m", false));

            assertThat(reporter.flushNow()).isFalse();
            RecordedRequest request = beacon.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/v1/client-events");
            assertThat(beacon.takeRequest(200, TimeUnit.MILLISECONDS))
                    .as("OkHttp's status follow-up engine is bypassed").isNull();
            assertThat(beacon.getRequestCount()).isEqualTo(1);
            assertThat(reporter.bufferedEvents()).as("the rejected batch is retained").hasSize(1);
        } finally {
            beacon.shutdown();
        }
    }

    // --- identity (§5.1) -----------------------------------------------------

    @Test void sdkIdentityUsesOnlyTheContractVocabulary() {
        JsonObject identity = Telemetry.sdkIdentity();
        assertThat(identity.get("name").getAsString()).isEqualTo("tr-java");
        assertThat(identity.get("lang").getAsString()).isEqualTo("java");
        assertThat(identity.get("version").getAsString())
                .matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+].+)?$");
        assertThat(identity.get("runtime").getAsString())
                .matches("^[a-z]{1,10}/[0-9A-Za-z.+-]{1,24}$");
        assertThat(identity.get("os").getAsString())
                .isIn("linux", "macos", "windows", "ios", "android", "freebsd", "other");
        assertThat(identity.get("arch").getAsString())
                .isIn("x64", "x32", "arm", "arm64", "wasm", "other");

        JsonObject forged = new JsonObject();
        forged.addProperty("name", "tr-evil");
        forged.addProperty("version", "not-semver");
        forged.addProperty("lang", "cobol");
        forged.addProperty("runtime", "Java/1.8.0_452");
        forged.addProperty("os", "plan9");
        forged.addProperty("arch", "mips");
        JsonObject normalised = Telemetry.normaliseSdkIdentity(forged);
        assertThat(normalised).isEqualTo(identity);
        JsonObject pinned = Telemetry.normaliseSdkIdentity(SDK);
        assertThat(pinned).isEqualTo(SDK);
    }

    // --- helpers -------------------------------------------------------------

    private TelemetryReporter reporter(
            TelemetryReporter.Sender sender, FakeClock clock, double sampleRate, Random random) {
        TelemetryReporter reporter = builder(sender, clock, sampleRate, random).build();
        reporters.add(reporter);
        return reporter;
    }

    private static TelemetryReporter.Builder builder(
            TelemetryReporter.Sender sender, FakeClock clock, double sampleRate, Random random) {
        TelemetryReporter.Builder builder = TelemetryReporter.builder()
                .controlBaseUrl("https://trustedrouter.com/v1")
                .apiKey(() -> "sk-tr-test")
                .sdkIdentity(SDK)
                .successSampleRate(sampleRate)
                .automaticWorker(false)
                .debug(Boolean.FALSE);
        if (sender != null) {
            builder.sender(sender);
        }
        if (clock != null) {
            builder.clock(clock);
        }
        if (random != null) {
            builder.random(random);
        }
        return builder;
    }

    private static JsonObject identity() {
        JsonObject identity = new JsonObject();
        identity.addProperty("name", "tr-java");
        identity.addProperty("version", "0.6.0");
        identity.addProperty("lang", "java");
        identity.addProperty("runtime", "java/17.0.20");
        identity.addProperty("os", "macos");
        identity.addProperty("arch", "arm64");
        return identity;
    }

    private static AttemptRecord attempt(int index, String outcome, Integer status) {
        AttemptRecord attempt = new AttemptRecord();
        attempt.index = index;
        attempt.host = "apex";
        attempt.outcome = outcome;
        attempt.httpStatus = status;
        attempt.errorClass = "transport_error".equals(outcome) ? "dns" : null;
        attempt.elapsedMs = 25L;
        attempt.ttfbMs = status == null ? null : Long.valueOf(20L);
        return attempt;
    }

    private static List<AttemptRecord> attempts(AttemptRecord... values) {
        return new ArrayList<AttemptRecord>(Arrays.asList(values));
    }

    private static RequestRecorder.Event event(
            String finalOutcome, List<AttemptRecord> attempts, long totalMs, String model,
            boolean failoverUsed) {
        AttemptRecord last = attempts.get(attempts.size() - 1);
        return new RequestRecorder.Event("responses", "POST", false, false, model, attempts,
                finalOutcome, last.httpStatus, totalMs, null, failoverUsed, "none",
                Long.valueOf(120_000L));
    }

    private static CounterKey key(
            String level, String endpoint, String host, String outcome, String errorClass,
            String statusClass) {
        return new CounterKey(level, endpoint, false, host, outcome, errorClass, statusClass,
                "none", false, false);
    }

    private static List<CounterUpdate> updates(CounterKey key) {
        CounterIncrement increment = new CounterIncrement(1L, 1L, 0L, 1L);
        increment.totalMsHist.put("lt100", Long.valueOf(1L));
        increment.firstEventMsHist.put("lt100", Long.valueOf(1L));
        return Collections.singletonList(new CounterUpdate(key, increment));
    }

    private static List<CounterUpdate> noCounters() {
        return Collections.emptyList();
    }

    private static void record(TelemetryReporter reporter) {
        record(reporter, event("ok", attempts(attempt(0, "ok", 200)), 25L, "model/a", false));
    }

    private static void record(TelemetryReporter reporter, RequestRecorder.Event event) {
        reporter.onRequest(event, updates(key("request", "responses", "apex", "ok", null, "2xx")));
    }

    private static List<CounterKey> product(
            List<String> endpoints, List<String> errorClasses, List<String> statuses,
            String level) {
        List<CounterKey> keys = new ArrayList<CounterKey>();
        for (String endpoint : endpoints) {
            for (String errorClass : errorClasses) {
                for (String status : statuses) {
                    keys.add(key(level, endpoint, "apex", "ok", errorClass, status));
                }
            }
        }
        return keys;
    }

    private static long totalRequests(Map<CounterKey, CounterIncrement> rows) {
        long total = 0L;
        for (CounterIncrement row : rows.values()) {
            total += row.requests;
        }
        return total;
    }

    private static CounterKey lastKey(Map<CounterKey, CounterIncrement> rows) {
        CounterKey last = null;
        for (CounterKey key : rows.keySet()) {
            last = key;
        }
        return last;
    }

    private static List<String> reasons(List<JsonObject> events) {
        List<String> reasons = new ArrayList<String>();
        for (JsonObject event : events) {
            reasons.add(event.get("sample_reason").getAsString());
        }
        return reasons;
    }

    private static List<String> models(List<JsonObject> events) {
        List<String> models = new ArrayList<String>();
        for (JsonObject event : events) {
            models.add(event.get("model").isJsonNull() ? null : event.get("model").getAsString());
        }
        return models;
    }

    private static TelemetryReporter.SendResult accepted() {
        return new TelemetryReporter.SendResult(202, null, null,
                "{\"data\":{\"accepted_events\":0,\"accepted_counters\":0,\"dropped\":0},"
                        + "\"policy\":{}}");
    }

    private static MockResponse accepted202() {
        return new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"accepted_events\":0,\"accepted_counters\":0,"
                        + "\"dropped\":0},\"policy\":{}}");
    }

    /** Every key in a serialised batch must belong to the schema (§5, §2.1). */
    static void assertSchemaKeysOnly(JsonObject batch) {
        Set<String> batchKeys = new HashSet<String>(Arrays.asList("schema_version", "batch_id",
                "instance_id", "seq", "sent_at_ms", "sdk", "synthetic", "dropped_since_last",
                "events", "counters"));
        Set<String> sdkKeys = new HashSet<String>(Arrays.asList("name", "version", "lang",
                "runtime", "os", "arch"));
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
        assertThat(batch.getAsJsonObject("sdk").keySet()).isSubsetOf(sdkKeys);
        for (JsonElement element : batch.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            assertThat(event.keySet()).isSubsetOf(eventKeys);
            for (JsonElement attempt : event.getAsJsonArray("attempts")) {
                assertThat(attempt.getAsJsonObject().keySet()).isSubsetOf(attemptKeys);
            }
        }
        for (JsonElement element : batch.getAsJsonArray("counters")) {
            JsonObject counter = element.getAsJsonObject();
            assertThat(counter.keySet()).isSubsetOf(counterKeys);
            assertThat(counter.getAsJsonObject("total_ms_hist").keySet())
                    .isSubsetOf(new HashSet<String>(Telemetry.LATENCY_BUCKETS));
            assertThat(counter.getAsJsonObject("first_event_ms_hist").keySet())
                    .isSubsetOf(new HashSet<String>(Telemetry.LATENCY_BUCKETS));
        }
    }

    static final class FakeClock implements TelemetryReporter.Clock {
        long nowMs;

        @Override public long monotonicNanos() {
            return nowMs * 1_000_000L;
        }

        @Override public long wallMillis() {
            return 1_700_000_000_000L + nowMs;
        }

        void advance(long millis) {
            nowMs += millis;
        }
    }

    /** Records every single-shot send and answers from a script (202 by default). */
    static final class FakeSender implements TelemetryReporter.Sender {
        final List<String> urls = new CopyOnWriteArrayList<String>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<Map<String, String>>();
        final List<String> bodies = new CopyOnWriteArrayList<String>();
        final List<JsonObject> batches = new CopyOnWriteArrayList<JsonObject>();
        final Deque<Object> script = new ArrayDeque<Object>();
        final java.util.concurrent.CountDownLatch delivered =
                new java.util.concurrent.CountDownLatch(1);

        @Override public synchronized TelemetryReporter.SendResult send(
                String url, Map<String, String> requestHeaders, byte[] body, Long timeoutMs)
                throws IOException {
            urls.add(url);
            headers.add(new HashMap<String, String>(requestHeaders));
            String text = new String(body, StandardCharsets.UTF_8);
            bodies.add(text);
            batches.add(JsonSupport.parse(text).getAsJsonObject());
            Object next = script.pollFirst();
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            delivered.countDown();
            return next == null ? accepted() : (TelemetryReporter.SendResult) next;
        }

        @Override public void close() {}
    }

    /** Hangs on send so the bounded close has something to bound. */
    static final class SlowSender implements TelemetryReporter.Sender {
        final java.util.concurrent.CountDownLatch closed =
                new java.util.concurrent.CountDownLatch(1);

        @Override public TelemetryReporter.SendResult send(
                String url, Map<String, String> requestHeaders, byte[] body, Long timeoutMs)
                throws IOException {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return accepted();
        }

        @Override public void close() {
            closed.countDown();
        }
    }

    @SuppressWarnings("serial")
    static final class ScriptedRandom extends Random {
        private final Iterator<Double> draws;

        ScriptedRandom(Double... values) {
            super(0L);
            this.draws = Arrays.asList(values).iterator();
        }

        @Override public double nextDouble() {
            return draws.hasNext() ? draws.next().doubleValue() : 1.0d;
        }
    }
}
