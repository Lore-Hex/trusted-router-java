package com.trustedrouter.receipts;

/** Required embedded attestation evidence is absent. */
public final class MissingAttestationException extends ReceiptAttestationException {
    private static final long serialVersionUID = 1L;

    public MissingAttestationException(String message) { super(message); }
}
