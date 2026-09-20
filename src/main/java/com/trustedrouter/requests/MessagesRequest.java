package com.trustedrouter.requests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.trustedrouter.CallOptions;

/** Anthropic-compatible Messages request. */
public final class MessagesRequest {
    private final JsonObject body;
    private final CallOptions callOptions;
    private MessagesRequest(Builder builder) {
        body = builder.body.deepCopy();
        if (!body.has("model")) { throw new IllegalStateException("model is required"); }
        if (!body.has("messages")) { body.add("messages", new JsonArray()); }
        if (!body.has("max_tokens")) { body.addProperty("max_tokens", 1024); }
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
        private final JsonArray messages = new JsonArray();
        private CallOptions callOptions;
        private Builder() { body.add("messages", messages); }
        /**
         * Sets model.
         *
         * @param value the model
         * @return this builder
         */
        public Builder model(String value) { body.addProperty("model", value); return this; }
        /**
         * Sets message.
         *
         * @param role the role
         * @param content the content
         * @return this builder
         */
        public Builder message(String role, String content) {
            messages.add(ChatRequest.message(role, content)); return this;
        }
        /**
         * Sets message.
         *
         * @param value the message
         * @return this builder
         */
        public Builder message(JsonObject value) { messages.add(value.deepCopy()); return this; }
        /**
         * Sets max tokens.
         *
         * @param value the max tokens
         * @return this builder
         */
        public Builder maxTokens(int value) { body.addProperty("max_tokens", value); return this; }
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
        public MessagesRequest build() { return new MessagesRequest(this); }
    }
}
