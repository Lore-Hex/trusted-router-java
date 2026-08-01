package com.trustedrouter.requests;

import com.google.gson.JsonObject;
import com.trustedrouter.CallOptions;

/** Exact-decimal checkout request. Pass amount as a decimal string, never a float. */
public final class BillingCheckoutRequest {
    private final JsonObject body;
    private final CallOptions callOptions;
    private BillingCheckoutRequest(Builder builder) {
        body = builder.body.deepCopy();
        if (!body.has("amount")) { throw new IllegalStateException("amount is required"); }
        callOptions = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
    }
    public static Builder builder() { return new Builder(); }
    public JsonObject toJson() { return body.deepCopy(); }
    public CallOptions getCallOptions() { return callOptions; }
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private CallOptions callOptions;
        private Builder() {}
        public Builder amount(String exactUsd) { body.addProperty("amount", exactUsd); return this; }
        public Builder paymentMethod(String value) { body.addProperty("payment_method", value); return this; }
        public Builder workspaceId(String value) { body.addProperty("workspace_id", value); return this; }
        public Builder successUrl(String value) { body.addProperty("success_url", value); return this; }
        public Builder cancelUrl(String value) { body.addProperty("cancel_url", value); return this; }
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        public BillingCheckoutRequest build() { return new BillingCheckoutRequest(this); }
    }
}
