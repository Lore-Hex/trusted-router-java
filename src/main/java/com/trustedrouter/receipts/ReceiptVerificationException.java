package com.trustedrouter.receipts;

import java.security.GeneralSecurityException;

/** Common base for every fail-closed inference receipt verification error. */
public class ReceiptVerificationException extends GeneralSecurityException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptVerificationException.
     *
     * @param message the message
     */
    public ReceiptVerificationException(String message) {
        super(message);
    }

    /**
     * Creates a ReceiptVerificationException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
