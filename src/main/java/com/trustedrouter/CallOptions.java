package com.trustedrouter;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-call authentication, routing, idempotency, and timeout overrides. */
public final class CallOptions {
    /**
     * The none.
     */
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

    /**
     * Creates a builder for this value.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Performs the has api key override operation.
     *
     * @return the has api key override
     */
    public boolean hasApiKeyOverride() { return apiKeySet; }
    /**
     * Returns api key.
     *
     * @return the api key
     */
    public String getApiKey() { return apiKey; }
    /**
     * Performs the has workspace id override operation.
     *
     * @return the has workspace id override
     */
    public boolean hasWorkspaceIdOverride() { return workspaceIdSet; }
    /**
     * Returns workspace id.
     *
     * @return the workspace id
     */
    public String getWorkspaceId() { return workspaceId; }
    /**
     * Returns idempotency key.
     *
     * @return the idempotency key
     */
    public String getIdempotencyKey() { return idempotencyKey; }
    /**
     * Performs the has timeout override operation.
     *
     * @return the has timeout override
     */
    public boolean hasTimeoutOverride() { return timeoutSet; }
    /**
     * Returns timeout millis.
     *
     * @return the timeout millis
     */
    public Long getTimeoutMillis() { return timeoutMillis; }
    /**
     * Returns headers.
     *
     * @return the headers
     */
    public Map<String, String> getHeaders() { return headers; }

    /**
     * Sets to builder.
     *
     * @return this builder
     */
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

    /**
     * Represents builder.
     */
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

        /**
         * Overrides the bearer for this call. An empty value suppresses authorization.
         *
         * @param value the api key
         * @return this builder
         */
        public Builder apiKey(String value) {
            this.apiKey = value;
            this.apiKeySet = true;
            return this;
        }

        /**
         * Sets without api key.
         *
         * @return this builder
         */
        public Builder withoutApiKey() {
            return apiKey("");
        }

        /**
         * Overrides the workspace selector. An empty value suppresses the header.
         *
         * @param value the workspace id
         * @return this builder
         */
        public Builder workspaceId(String value) {
            this.workspaceId = value;
            this.workspaceIdSet = true;
            return this;
        }

        /**
         * Sets without workspace.
         *
         * @return this builder
         */
        public Builder withoutWorkspace() {
            return workspaceId("");
        }

        /**
         * Sets idempotency key.
         *
         * @param value the idempotency key
         * @return this builder
         */
        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        /**
         * Sets timeout.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeoutMillis = Long.valueOf(TrustedRouter.timeoutMillis(value));
            this.timeoutSet = true;
            return this;
        }

        /**
         * Sets the timeout in milliseconds without requiring {@link Duration}.
         *
         * @param value the timeout millis
         * @return this builder
         */
        public Builder timeoutMillis(long value) {
            if (value < 0L) { throw new IllegalArgumentException("timeout must be non-negative"); }
            this.timeoutMillis = Long.valueOf(value);
            this.timeoutSet = true;
            return this;
        }

        /**
         * Sets no timeout.
         *
         * @return this builder
         */
        public Builder noTimeout() {
            this.timeoutMillis = null;
            this.timeoutSet = true;
            return this;
        }

        /**
         * Sets header.
         *
         * @param name the name
         * @param value the header
         * @return this builder
         */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Sets headers.
         *
         * @param values the values
         * @return this builder
         */
        public Builder headers(Map<String, String> values) {
            this.headers.clear();
            if (values != null) {
                this.headers.putAll(values);
            }
            return this;
        }

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public CallOptions build() {
            return new CallOptions(this);
        }
    }
}
