package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 4xx request error without a more specific SDK type. */
public final class BadRequestException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    public BadRequestException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
