package com.trustedrouter;

import com.trustedrouter.internal.RetryPolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import okhttp3.OkHttpClient;

/** Immutable options for {@link TrustedRouterClient}. */
public final class TrustedRouterOptions {
    final String apiKey;
    final String baseUrl;
    final String controlBaseUrl;
    final String statusUrl;
    final String trustReleaseUrl;
    final OkHttpClient httpClient;
    final Long timeoutMillis;
    final Map<String, String> headers;
    final String workspaceId;
    final int maxRetries;
    final boolean regionalFailover;
    final Boolean telemetry;
    final double telemetrySampleRate;
    final Executor asyncExecutor;

    private TrustedRouterOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = normalizeBaseUrl(builder.baseUrl, TrustedRouter.DEFAULT_API_BASE_URL);
        this.controlBaseUrl = normalizeBaseUrl(
                builder.controlBaseUrl, TrustedRouter.DEFAULT_CONTROL_BASE_URL);
        this.statusUrl = normalizeBaseUrl(builder.statusUrl, TrustedRouter.DEFAULT_STATUS_URL);
        this.trustReleaseUrl = normalizeBaseUrl(
                builder.trustReleaseUrl, TrustedRouter.DEFAULT_TRUST_RELEASE_URL);
        this.httpClient = builder.httpClient;
        this.timeoutMillis = builder.timeoutMillis;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.headers));
        this.workspaceId = builder.workspaceId;
        this.maxRetries = builder.maxRetries;
        this.regionalFailover = builder.regionalFailover;
        this.telemetry = builder.telemetry;
        this.telemetrySampleRate = builder.telemetrySampleRate;
        this.asyncExecutor = builder.asyncExecutor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getControlBaseUrl() { return controlBaseUrl; }
    public String getStatusUrl() { return statusUrl; }
    public String getTrustReleaseUrl() { return trustReleaseUrl; }
    public OkHttpClient getHttpClient() { return httpClient; }
    public Long getTimeoutMillis() { return timeoutMillis; }
    public Map<String, String> getHeaders() { return headers; }
    public String getWorkspaceId() { return workspaceId; }
    public int getMaxRetries() { return maxRetries; }
    public boolean isRegionalFailover() { return regionalFailover; }
    /** Tri-state client-telemetry override; null means resolve from env and hosts. */
    public Boolean getTelemetry() { return telemetry; }
    /** Random sampling rate for healthy first-attempt successes in the beacon channel. */
    public double getTelemetrySampleRate() { return telemetrySampleRate; }
    public Executor getAsyncExecutor() { return asyncExecutor; }

    private static String normalizeBaseUrl(String value, String fallback) {
        String normalized = value == null || value.trim().isEmpty() ? fallback : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            throw new IllegalArgumentException("base URL must use http or https");
        }
        return normalized;
    }

    /** Builder with production-safe defaults. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String controlBaseUrl;
        private String statusUrl;
        private String trustReleaseUrl;
        private OkHttpClient httpClient;
        private Long timeoutMillis = Long.valueOf(TrustedRouter.DEFAULT_REQUEST_TIMEOUT_MILLIS);
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private String workspaceId;
        private int maxRetries = RetryPolicy.DEFAULT_MAX_RETRIES;
        private boolean regionalFailover = true;
        private Boolean telemetry;
        private double telemetrySampleRate = 0.01d;
        private Executor asyncExecutor;

        private Builder() {}

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder controlBaseUrl(String value) {
            this.controlBaseUrl = value;
            return this;
        }

        public Builder statusUrl(String value) {
            this.statusUrl = value;
            return this;
        }

        public Builder trustReleaseUrl(String value) {
            this.trustReleaseUrl = value;
            return this;
        }

        public Builder httpClient(OkHttpClient value) {
            this.httpClient = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeoutMillis = Long.valueOf(TrustedRouter.timeoutMillis(value));
            return this;
        }

        /** Sets the SDK call timeout in milliseconds without requiring {@link Duration}. */
        public Builder timeoutMillis(long value) {
            if (value < 0L) {
                throw new IllegalArgumentException("timeout must be non-negative");
            }
            this.timeoutMillis = Long.valueOf(value);
            return this;
        }

        /** Disables the SDK timeout. The caller's OkHttp timeouts still apply. */
        public Builder noTimeout() {
            this.timeoutMillis = null;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> values) {
            this.headers.clear();
            if (values != null) {
                this.headers.putAll(values);
            }
            return this;
        }

        public Builder workspaceId(String value) {
            this.workspaceId = value;
            return this;
        }

        public Builder maxRetries(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("maxRetries must be non-negative");
            }
            this.maxRetries = value;
            return this;
        }

        public Builder regionalFailover(boolean value) {
            this.regionalFailover = value;
            return this;
        }

        /**
         * Explicitly enables or disables client reliability telemetry: the
         * content-free {@code x-tr-client} request header AND the beacon
         * channel, which posts bounded, content-free batches of sampled
         * events and exact per-minute counters to the control plane from
         * the SDK's own background thread. Overrides the
         * {@code TRUSTEDROUTER_TELEMETRY} and {@code DO_NOT_TRACK}
         * environment variables. Left unset (null), telemetry follows those
         * variables and otherwise defaults on only when both the inference
         * base and the control host are TrustedRouter's own; custom base
         * URLs default off. If explicitly enabled for a custom inference
         * URL, its raw hostname is reduced to the {@code unknown} enum. Set
         * {@code TRUSTEDROUTER_TELEMETRY_DEBUG=1} to echo every batch to
         * stderr before it is sent.
         */
        public Builder telemetry(Boolean value) {
            this.telemetry = value;
            return this;
        }

        /**
         * Random sampling rate, in {@code [0, 1]}, for telemetry events of
         * healthy, fast, first-attempt successes (default 0.01). Failures,
         * retried or failed-over calls, and calls slower than 30 s are always
         * retained, and the exact per-minute counters are never sampled. The
         * control plane may lower the rate but never raise it.
         */
        public Builder telemetrySampleRate(double value) {
            if (!(value >= 0.0d && value <= 1.0d)) {
                throw new IllegalArgumentException("telemetrySampleRate must be within [0, 1]");
            }
            this.telemetrySampleRate = value;
            return this;
        }

        public Builder asyncExecutor(Executor value) {
            this.asyncExecutor = value;
            return this;
        }

        public TrustedRouterOptions build() {
            return new TrustedRouterOptions(this);
        }
    }
}
