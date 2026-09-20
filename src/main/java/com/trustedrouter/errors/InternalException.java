package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 5xx gateway/upstream error or exhausted transport retry. */
public final class InternalException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a InternalException.
     *
     * @param status the status
     * @param message the message
     * @param payload the payload
     */
    public InternalException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }

    /**
     * Creates a InternalException.
     *
     * @param status the status
     * @param message the message
     * @param payload the payload
     * @param cause the cause
     */
    public InternalException(int status, String message, JsonElement payload, Throwable cause) {
        super(status, message, payload, cause);
    }
}
