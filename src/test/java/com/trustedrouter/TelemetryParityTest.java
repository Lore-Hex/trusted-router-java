package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.internal.Telemetry;
import org.junit.jupiter.api.Test;

/**
 * Pins the client telemetry contract v1 vocabulary and bounds (§5.2/§6.2/§6.4)
 * so every sibling SDK shares byte-identical enums and limits. These values
 * mirror the Python SDK's {@code _constants.py} and the server's
 * {@code client_events_schema.py}; changing any of them is a coordinated
 * cross-SDK release, never a local edit.
 */
final class TelemetryParityTest {
    @Test void beaconPathAndSchemaVersionArePinned() {
        assertThat(Telemetry.CLIENT_EVENTS_PATH).isEqualTo("/client-events");
        assertThat(Telemetry.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test void beaconBoundsMatchThePythonConstants() {
        assertThat(Telemetry.FLUSH_INTERVAL_MS).isEqualTo(30_000L);
        assertThat(Telemetry.MAX_EVENTS).isEqualTo(1_000);
        assertThat(Telemetry.MAX_BATCH_EVENTS).isEqualTo(100);
        assertThat(Telemetry.MAX_BATCH_COUNTERS).isEqualTo(200);
        assertThat(Telemetry.MAX_WINDOW_KEYS).isEqualTo(256);
        assertThat(Telemetry.RETENTION_MS).isEqualTo(86_400_000L);
        assertThat(Telemetry.RETENTION_BYTES).isEqualTo(524_288L);
        assertThat(Telemetry.BACKOFF_MIN_MS).isEqualTo(60_000L);
        assertThat(Telemetry.BACKOFF_MAX_MS).isEqualTo(600_000L);
        assertThat(Telemetry.MAX_RETRY_AFTER_MS).isEqualTo(600_000L);
        assertThat(Telemetry.MAX_PAUSE_MS).isEqualTo(86_400_000L);
        assertThat(Telemetry.MAX_BATCH_BYTES).isEqualTo(65_536);
        assertThat(Telemetry.BATCH_TRIGGER_BYTES).isEqualTo(61_440);
        assertThat(Telemetry.BATCH_TRIGGER_EVENTS).isEqualTo(50);
        assertThat(Telemetry.DEFAULT_SUCCESS_SAMPLE_RATE).isEqualTo(0.01d);
        assertThat(Telemetry.SLOW_REQUEST_MS).isEqualTo(30_000L);
        assertThat(Telemetry.FINAL_FLUSH_MS).isEqualTo(2_000L);
        assertThat(Telemetry.MAX_DURATION_MS).isEqualTo(3_600_000L);
        assertThat(Telemetry.MAX_AGE_MS).isEqualTo(86_400_000L);
    }

    @Test void finalOutcomeTimeoutPhaseStatusClassAndBucketVocabulariesArePinned() {
        assertThat(Telemetry.FINAL_OUTCOMES).containsExactly(
                "ok", "http_error", "transport_error", "timeout", "stream_broken",
                "aborted", "exhausted");
        assertThat(Telemetry.TIMEOUT_PHASES).containsExactly(
                "none", "connect", "first_byte", "idle", "total");
        assertThat(Telemetry.HTTP_STATUS_CLASSES).containsExactly(
                "none", "2xx", "4xx", "429", "5xx");
        assertThat(Telemetry.LATENCY_BUCKETS).containsExactly(
                "lt100", "lt200", "lt400", "lt800", "lt1600", "lt3200", "lt6400",
                "lt12800", "lt25600", "lt51200", "lt102400", "ge102400");
        assertThat(Telemetry.ERROR_SOURCES).containsExactly("router", "provider", "unknown");
        assertThat(Telemetry.SAMPLE_REASONS).containsExactly(
                "failure", "retried", "slow", "random");
        assertThat(Telemetry.LEVELS).containsExactly("attempt", "request");
        // Module-wins ruling: the schema accepts only GET and POST.
        assertThat(Telemetry.BEACON_METHODS).containsExactly("GET", "POST");
    }

    @Test void hostVocabularyIsPinned() {
        assertThat(Telemetry.HOSTS).containsExactly(
                "apex", "ally", "uptime", "us_central1", "us_east4",
                "europe_west4", "control", "custom");
    }

    @Test void endpointVocabularyIsPinned() {
        assertThat(Telemetry.ENDPOINTS).containsExactly(
                "chat_completions", "messages", "responses", "embeddings",
                "images", "videos", "models", "fusion", "control_other",
                "inference_other");
    }

    @Test void outcomeVocabularyIsPinned() {
        assertThat(Telemetry.OUTCOMES).containsExactly(
                "ok", "http_error", "transport_error", "timeout",
                "stream_broken", "aborted");
    }

    @Test void errorClassVocabularyIsPinned() {
        assertThat(Telemetry.ERROR_CLASSES).containsExactly(
                "dns", "tls", "connect_refused", "connect_timeout",
                "connect_error", "read_timeout", "write_timeout", "pool_timeout",
                "protocol_error", "reset", "io_error", "proxy_error",
                "stream_stalled", "unknown");
    }

    @Test void regionBaseUrlsMatchThePythonSdkConstants() {
        assertThat(Telemetry.REGION_BASE_URLS).containsExactly(
                "https://api-us-central1.quillrouter.com/v1",
                "https://api-us-east4.quillrouter.com/v1",
                "https://api-europe-west4.quillrouter.com/v1");
    }

    @Test void hostMappingCoversEveryKnownTrustedRouterHost() {
        assertThat(Telemetry.hostEnum(TrustedRouter.DEFAULT_API_BASE_URL)).isEqualTo("apex");
        assertThat(Telemetry.hostEnum("https://api.trustedrouter.com/v1")).isEqualTo("apex");
        assertThat(Telemetry.hostEnum("https://api.allyrouter.com/v1")).isEqualTo("ally");
        assertThat(Telemetry.hostEnum("https://api.uptimerouter.com/v1")).isEqualTo("uptime");
        assertThat(Telemetry.hostEnum("https://api-us-central1.quillrouter.com/v1"))
                .isEqualTo("us_central1");
        assertThat(Telemetry.hostEnum("https://api-us-east4.quillrouter.com/v1"))
                .isEqualTo("us_east4");
        assertThat(Telemetry.hostEnum("https://api-europe-west4.quillrouter.com/v1"))
                .isEqualTo("europe_west4");
        assertThat(Telemetry.hostEnum(TrustedRouter.DEFAULT_CONTROL_BASE_URL))
                .isEqualTo("control");
        assertThat(Telemetry.hostEnum("https://trustedrouter.com")).isEqualTo("control");
        assertThat(Telemetry.hostEnum("https://eu.trustedrouter.com/v1")).isEqualTo("control");
    }

    @Test void anythingElseIsCustomAndNeverOnTheWire() {
        assertThat(Telemetry.hostEnum("https://my.internal/v1")).isEqualTo("custom");
        // Scheme is part of identity: plain http is NOT a TrustedRouter host.
        assertThat(Telemetry.hostEnum("http://api.trustedrouter.com/v1")).isEqualTo("custom");
        assertThat(Telemetry.hostEnum("http://trustedrouter.com/v1")).isEqualTo("custom");
        // A lookalike suffix without the dot boundary is not a subdomain.
        assertThat(Telemetry.hostEnum("https://eviltrustedrouter.com/v1")).isEqualTo("custom");
        assertThat(Telemetry.hostEnum("api.trustedrouter.com")).isEqualTo("custom");
        assertThat(Telemetry.hostEnum("")).isEqualTo("custom");
        assertThat(Telemetry.hostEnum(null)).isEqualTo("custom");
    }
}
