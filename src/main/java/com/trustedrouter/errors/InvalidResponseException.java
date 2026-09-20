package com.trustedrouter.errors;

/** Malformed consumed fields passed directly to the public model decoder. */
public final class InvalidResponseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidResponseException(String message) { super(message); }
}
