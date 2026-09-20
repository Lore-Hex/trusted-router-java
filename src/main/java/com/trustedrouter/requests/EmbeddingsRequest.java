package com.trustedrouter.requests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.CallOptions;

/** OpenAI-compatible embeddings request. */
public final class EmbeddingsRequest {
    private final JsonObject body;
    private final CallOptions callOptions;
    private EmbeddingsRequest(Builder builder) {
        body = builder.body.deepCopy();
        if (!body.has("model") || !body.has("input")) {
            throw new IllegalStateException("model and input are required");
        }
        callOptions = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
    }
    /**
     * Creates a builder for this value.
     *
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }
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
        private Builder() {}
        /**
         * Sets model.
         *
         * @param value the model
         * @return this builder
         */
        public Builder model(String value) { body.addProperty("model", value); return this; }
        /**
         * Sets input.
         *
         * @param value the input
         * @return this builder
         */
        public Builder input(String value) { body.addProperty("input", value); return this; }
        /**
         * Sets input.
         *
         * @param value the input
         * @return this builder
         */
        public Builder input(JsonElement value) { body.add("input", value.deepCopy()); return this; }
        /**
         * Sets encoding format.
         *
         * @param value the encoding format
         * @return this builder
         */
        public Builder encodingFormat(String value) { body.addProperty("encoding_format", value); return this; }
        /**
         * Sets dimensions.
         *
         * @param value the dimensions
         * @return this builder
         */
        public Builder dimensions(int value) { body.addProperty("dimensions", value); return this; }
        /**
         * Sets user.
         *
         * @param value the user
         * @return this builder
         */
        public Builder user(String value) { body.addProperty("user", value); return this; }
        /**
         * Sets provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            body.add("provider", value.toJson()); return this;
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
        public EmbeddingsRequest build() { return new EmbeddingsRequest(this); }
    }
}
