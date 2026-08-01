package com.trustedrouter.errors;

import com.google.gson.JsonElement;

/** A 403 authorization failure. */
public final class PermissionDeniedException extends TrustedRouterException {
    private static final long serialVersionUID = 1L;
    public PermissionDeniedException(int status, String message, JsonElement payload) {
        super(status, message, payload);
    }
}
