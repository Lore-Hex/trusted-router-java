package com.trustedrouter.requests;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouter;

/** Stateless OpenAI Responses API request. */
public final class ResponsesRequest {
    private final JsonObject body;
    private final CallOptions callOptions;

    private ResponsesRequest(Builder builder) {
        this.body = builder.body.deepCopy();
        if (!body.has("model")) { body.addProperty("model", TrustedRouter.AUTO_MODEL); }
        if (!body.has("input")) { throw new IllegalStateException("input is required"); }
        this.callOptions = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
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
     * @param stream the stream
     * @return the wire JSON representation
     */
    public JsonObject toJson(boolean stream) {
        JsonObject value = body.deepCopy();
        value.addProperty("stream", stream);
        return value;
    }
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
         * Sets instructions.
         *
         * @param value the instructions
         * @return this builder
         */
        public Builder instructions(String value) { body.addProperty("instructions", value); return this; }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, JsonElement value) {
            body.add(name, value == null ? JsonNull.INSTANCE : value.deepCopy()); return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, String value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)); return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, Number value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)); return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, boolean value) { body.addProperty(name, value); return this; }
        /**
         * Sets provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            return parameter("provider", value.toJson());
        }
        /**
         * Sets call options.
         *
         * @param value the call options
         * @return this builder
         */
        public Builder callOptions(CallOptions value) { this.callOptions = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public ResponsesRequest build() { return new ResponsesRequest(this); }
    }
}
