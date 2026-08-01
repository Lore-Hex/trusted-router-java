package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 5xx gateway/upstream error or exhausted transport retry. */
public final class InternalException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;

    public InternalException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }

    public InternalException(int status, String message, JsonElement payload, Throwable cause) {
        super(status, message, payload, cause);
    }
}
