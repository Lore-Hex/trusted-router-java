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
    public static Builder builder() { return new Builder(); }
    public JsonObject toJson() { return body.deepCopy(); }
    public CallOptions getCallOptions() { return callOptions; }
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private final JsonArray messages = new JsonArray();
        private CallOptions callOptions;
        private Builder() { body.add("messages", messages); }
        public Builder model(String value) { body.addProperty("model", value); return this; }
        public Builder message(String role, String content) {
            messages.add(ChatRequest.message(role, content)); return this;
        }
        public Builder message(JsonObject value) { messages.add(value.deepCopy()); return this; }
        public Builder maxTokens(int value) { body.addProperty("max_tokens", value); return this; }
        public Builder parameter(String name, JsonElement value) {
            body.add(name, value == null ? JsonNull.INSTANCE : value.deepCopy()); return this;
        }
        public Builder parameter(String name, String value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)); return this;
        }
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            body.add("provider", value.toJson()); return this;
        }
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        public MessagesRequest build() { return new MessagesRequest(this); }
    }
}
