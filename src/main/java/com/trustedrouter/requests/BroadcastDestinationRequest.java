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
    public static Builder builder(String type) { return new Builder(type); }
    public JsonObject toJson() { return body.deepCopy(); }
    public CallOptions getCallOptions() { return callOptions; }
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private CallOptions callOptions;
        private Builder(String type) { body.addProperty("type", type); }
        public Builder name(String value) { body.addProperty("name", value); return this; }
        public Builder endpoint(String value) { body.addProperty("endpoint", value); return this; }
        public Builder enabled(boolean value) { body.addProperty("enabled", value); return this; }
        public Builder includeContent(boolean value) { body.addProperty("include_content", value); return this; }
        public Builder method(String value) { body.addProperty("method", value); return this; }
        public Builder apiKey(String value) { body.addProperty("api_key", value); return this; }
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
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        public BroadcastDestinationRequest build() { return new BroadcastDestinationRequest(this); }
    }
}
