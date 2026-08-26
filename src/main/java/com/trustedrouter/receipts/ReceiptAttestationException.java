package com.trustedrouter.receipts;

/** The receipt signing key is not bound by a valid attestation. */
public class ReceiptAttestationException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptAttestationException(String message) { super(message); }
    public ReceiptAttestationException(String message, Throwable cause) { super(message, cause); }
}
