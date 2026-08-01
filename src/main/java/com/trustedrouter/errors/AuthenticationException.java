package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 401 authentication failure. */
public final class AuthenticationException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    public AuthenticationException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
