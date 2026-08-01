package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A stable 501 response for an intentionally unsupported compatibility endpoint. */
public final class EndpointNotSupportedException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    public EndpointNotSupportedException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
