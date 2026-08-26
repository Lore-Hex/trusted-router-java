package com.trustedrouter.receipts;

/** The receipt does not echo the caller's expected nonce. */
public final class ReceiptNonceException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    public ReceiptNonceException(String message) { super(message); }
}
