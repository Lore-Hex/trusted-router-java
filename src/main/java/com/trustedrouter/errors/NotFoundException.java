package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 404 missing-resource response. */
public final class NotFoundException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    public NotFoundException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
