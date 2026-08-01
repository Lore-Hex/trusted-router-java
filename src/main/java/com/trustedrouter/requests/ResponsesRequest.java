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

    public static Builder builder() { return new Builder(); }
    public JsonObject toJson(boolean stream) {
        JsonObject value = body.deepCopy();
        value.addProperty("stream", stream);
        return value;
    }
    public CallOptions getCallOptions() { return callOptions; }

    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private CallOptions callOptions;
        private Builder() {}
        public Builder model(String value) { body.addProperty("model", value); return this; }
        public Builder input(String value) { body.addProperty("input", value); return this; }
        public Builder input(JsonElement value) { body.add("input", value.deepCopy()); return this; }
        public Builder instructions(String value) { body.addProperty("instructions", value); return this; }
        public Builder parameter(String name, JsonElement value) {
            body.add(name, value == null ? JsonNull.INSTANCE : value.deepCopy()); return this;
        }
        public Builder parameter(String name, String value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)); return this;
        }
        public Builder parameter(String name, Number value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value)); return this;
        }
        public Builder parameter(String name, boolean value) { body.addProperty(name, value); return this; }
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            return parameter("provider", value.toJson());
        }
        public Builder callOptions(CallOptions value) { this.callOptions = value; return this; }
        public ResponsesRequest build() { return new ResponsesRequest(this); }
    }
}
