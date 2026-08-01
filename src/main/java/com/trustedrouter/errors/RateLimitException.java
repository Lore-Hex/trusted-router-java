package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 429 response, including the numeric Retry-After value when present. */
public final class RateLimitException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    private final Double retryAfterSeconds;

    public RateLimitException(
            int status, String message, JsonElement payload, Double retryAfterSeconds) {
        super(status, message, payload);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Double getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
