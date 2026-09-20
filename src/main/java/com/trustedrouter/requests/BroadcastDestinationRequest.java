package com.trustedrouter.requests;

import com.google.gson.JsonObject;
import com.trustedrouter.CallOptions;
import java.util.Map;

/** PostHog or OTLP webhook Broadcast destination request. */
public final class BroadcastDestinationRequest {
    private final JsonObject body;
    private final CallOptions callOptions;
    private BroadcastDestinationRequest(Builder builder) {
        body = builder.body.deepCopy();
        if (!body.has("type")) { throw new IllegalStateException("type is required"); }
        callOptions = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
    }
    /**
     * Creates a builder for this value.
     *
     * @param type the type
     * @return a new builder
     */
    public static Builder builder(String type) { return new Builder(type); }
    /**
     * Returns the wire JSON representation.
     *
     * @return the wire JSON representation
     */
    public JsonObject toJson() { return body.deepCopy(); }
    /**
     * Returns call options.
     *
     * @return the call options
     */
    public CallOptions getCallOptions() { return callOptions; }
    /**
     * Represents builder.
     */
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private CallOptions callOptions;
        private Builder(String type) { body.addProperty("type", type); }
        /**
         * Sets name.
         *
         * @param value the name
         * @return this builder
         */
        public Builder name(String value) { body.addProperty("name", value); return this; }
        /**
         * Sets endpoint.
         *
         * @param value the endpoint
         * @return this builder
         */
        public Builder endpoint(String value) { body.addProperty("endpoint", value); return this; }
        /**
         * Sets enabled.
         *
         * @param value the enabled
         * @return this builder
         */
        public Builder enabled(boolean value) { body.addProperty("enabled", value); return this; }
        /**
         * Sets include content.
         *
         * @param value the include content
         * @return this builder
         */
        public Builder includeContent(boolean value) { body.addProperty("include_content", value); return this; }
        /**
         * Sets method.
         *
         * @param value the method
         * @return this builder
         */
        public Builder method(String value) { body.addProperty("method", value); return this; }
        /**
         * Sets api key.
         *
         * @param value the api key
         * @return this builder
         */
        public Builder apiKey(String value) { body.addProperty("api_key", value); return this; }
        /**
         * Sets headers.
         *
         * @param values the values
         * @return this builder
         */
        public Builder headers(Map<String, String> values) {
            JsonObject object = new JsonObject();
            if (values != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    object.addProperty(entry.getKey(), entry.getValue());
                }
            }
            body.add("headers", object);
            return this;
        }
        /**
         * Sets call options.
         *
         * @param value the call options
         * @return this builder
         */
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public BroadcastDestinationRequest build() { return new BroadcastDestinationRequest(this); }
    }
}
