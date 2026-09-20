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
         * Sets amount.
         *
         * @param exactUsd the exact usd
         * @return this builder
         */
        public Builder amount(String exactUsd) { body.addProperty("amount", exactUsd); return this; }
        /**
         * Sets payment method.
         *
         * @param value the payment method
         * @return this builder
         */
        public Builder paymentMethod(String value) { body.addProperty("payment_method", value); return this; }
        /**
         * Sets workspace id.
         *
         * @param value the workspace id
         * @return this builder
         */
        public Builder workspaceId(String value) { body.addProperty("workspace_id", value); return this; }
        /**
         * Sets success url.
         *
         * @param value the success url
         * @return this builder
         */
        public Builder successUrl(String value) { body.addProperty("success_url", value); return this; }
        /**
         * Sets cancel url.
         *
         * @param value the cancel url
         * @return this builder
         */
        public Builder cancelUrl(String value) { body.addProperty("cancel_url", value); return this; }
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
        public BillingCheckoutRequest build() { return new BillingCheckoutRequest(this); }
    }
}
