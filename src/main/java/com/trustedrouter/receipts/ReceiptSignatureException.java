package com.trustedrouter.receipts;

/** The Ed25519 signature is invalid or cannot be checked. */
public final class ReceiptSignatureException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptSignatureException(String message) { super(message); }
    public ReceiptSignatureException(String message, Throwable cause) { super(message, cause); }
}
