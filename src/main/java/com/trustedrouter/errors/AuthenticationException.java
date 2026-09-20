package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 401 authentication failure. */
public final class AuthenticationException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    /**
     * Creates a AuthenticationException.
     *
     * @param status the status
     * @param message the message
     * @param payload the payload
     */
    public AuthenticationException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
