package com.trustedrouter;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-call authentication, routing, idempotency, and timeout overrides. */
public final class CallOptions {
    public static final CallOptions NONE = builder().build();

    final String apiKey;
    final boolean apiKeySet;
    final String workspaceId;
    final boolean workspaceIdSet;
    final String idempotencyKey;
    final Long timeoutMillis;
    final boolean timeoutSet;
    final Map<String, String> headers;

    private CallOptions(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiKeySet = builder.apiKeySet;
        this.workspaceId = builder.workspaceId;
        this.workspaceIdSet = builder.workspaceIdSet;
        this.idempotencyKey = builder.idempotencyKey;
        this.timeoutMillis = builder.timeoutMillis;
        this.timeoutSet = builder.timeoutSet;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.headers));
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasApiKeyOverride() { return apiKeySet; }
    public String getApiKey() { return apiKey; }
    public boolean hasWorkspaceIdOverride() { return workspaceIdSet; }
    public String getWorkspaceId() { return workspaceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean hasTimeoutOverride() { return timeoutSet; }
    public Long getTimeoutMillis() { return timeoutMillis; }
    public Map<String, String> getHeaders() { return headers; }

    public Builder toBuilder() {
        Builder builder = builder().headers(headers).idempotencyKey(idempotencyKey);
        if (apiKeySet) { builder.apiKey(apiKey); }
        if (workspaceIdSet) { builder.workspaceId(workspaceId); }
        if (timeoutSet) {
            if (timeoutMillis == null) { builder.noTimeout(); }
            else { builder.timeoutMillis(timeoutMillis.longValue()); }
        }
        return builder;
    }

    public static final class Builder {
        private String apiKey;
        private boolean apiKeySet;
        private String workspaceId;
        private boolean workspaceIdSet;
        private String idempotencyKey;
        private Long timeoutMillis;
        private boolean timeoutSet;
        private final Map<String, String> headers = new LinkedHashMap<String, String>();

        private Builder() {}

        /** Overrides the bearer for this call. An empty value suppresses authorization. */
        public Builder apiKey(String value) {
            this.apiKey = value;
            this.apiKeySet = true;
            return this;
        }

        public Builder withoutApiKey() {
            return apiKey("");
        }

        /** Overrides the workspace selector. An empty value suppresses the header. */
        public Builder workspaceId(String value) {
            this.workspaceId = value;
            this.workspaceIdSet = true;
            return this;
        }

        public Builder withoutWorkspace() {
            return workspaceId("");
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeoutMillis = Long.valueOf(TrustedRouter.timeoutMillis(value));
            this.timeoutSet = true;
            return this;
        }

        /** Sets the timeout in milliseconds without requiring {@link Duration}. */
        public Builder timeoutMillis(long value) {
            if (value < 0L) { throw new IllegalArgumentException("timeout must be non-negative"); }
            this.timeoutMillis = Long.valueOf(value);
            this.timeoutSet = true;
            return this;
        }

        public Builder noTimeout() {
            this.timeoutMillis = null;
            this.timeoutSet = true;
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

        public CallOptions build() {
            return new CallOptions(this);
        }
    }
}
