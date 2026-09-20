package com.trustedrouter.receipts;

/** Required embedded attestation evidence is absent. */
public final class MissingAttestationException extends ReceiptAttestationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a MissingAttestationException.
     *
     * @param message the message
     */
    public MissingAttestationException(String message) { super(message); }
}
