package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 429 response, including the numeric Retry-After value when present. */
public final class RateLimitException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    /** Serialized retry after seconds. */
    private final Double retryAfterSeconds;

    /**
     * Creates a RateLimitException.
     *
     * @param status the status
     * @param message the message
     * @param payload the payload
     * @param retryAfterSeconds the retry after seconds
     */
    public RateLimitException(
            int status, String message, JsonElement payload, Double retryAfterSeconds) {
        super(status, message, payload);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns retry after seconds.
     *
     * @return the retry after seconds
     */
    public Double getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
