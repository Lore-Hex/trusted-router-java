package com.trustedrouter.receipts;

/** The receipt issuer is invalid or does not match the caller's pin. */
public final class ReceiptIssuerException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptIssuerException.
     *
     * @param message the message
     */
    public ReceiptIssuerException(String message) { super(message); }

    /**
     * Creates a ReceiptIssuerException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptIssuerException(String message, Throwable cause) { super(message, cause); }
}
