package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.internal.Telemetry;
import org.junit.jupiter.api.Test;

/**
 * Pins the client telemetry contract v1 vocabulary (§5.2/§6.4) so the later
 * beacon PR — and every sibling SDK — shares byte-identical enums. These
 * values mirror the Python SDK's {@code _constants.py} and the server's
 * {@code client_events_schema.py}; changing any of them is a coordinated
 * cross-SDK release, never a local edit.
 */
final class TelemetryParityTest {
    @Test void beaconPathAndSchemaVersionArePinned() {
        assertThat(Telemetry.CLIENT_EVENTS_PATH).isEqualTo("/client-events");
        assertThat(Telemetry.SCHEMA_VERSION).isEqualTo(1);
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
