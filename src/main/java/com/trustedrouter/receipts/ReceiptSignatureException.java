package com.trustedrouter.receipts;

/** The Ed25519 signature is invalid or cannot be checked. */
public final class ReceiptSignatureException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptSignatureException.
     *
     * @param message the message
     */
    public ReceiptSignatureException(String message) { super(message); }
    /**
     * Creates a ReceiptSignatureException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptSignatureException(String message, Throwable cause) { super(message, cause); }
}
