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
    public static Builder builder() { return new Builder(); }
    public JsonObject toJson() { return body.deepCopy(); }
    public CallOptions getCallOptions() { return callOptions; }
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private CallOptions callOptions;
        private Builder() {}
        public Builder model(String value) { body.addProperty("model", value); return this; }
        public Builder input(String value) { body.addProperty("input", value); return this; }
        public Builder input(JsonElement value) { body.add("input", value.deepCopy()); return this; }
        public Builder encodingFormat(String value) { body.addProperty("encoding_format", value); return this; }
        public Builder dimensions(int value) { body.addProperty("dimensions", value); return this; }
        public Builder user(String value) { body.addProperty("user", value); return this; }
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            body.add("provider", value.toJson()); return this;
        }
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        public EmbeddingsRequest build() { return new EmbeddingsRequest(this); }
    }
}
