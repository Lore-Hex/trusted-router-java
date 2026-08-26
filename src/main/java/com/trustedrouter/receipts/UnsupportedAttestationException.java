package com.trustedrouter.receipts;

/** The receipt uses an attestation kind this SDK cannot verify. */
public final class UnsupportedAttestationException extends ReceiptAttestationException {
    private static final long serialVersionUID = 1L;

    public UnsupportedAttestationException(String message) { super(message); }
}
