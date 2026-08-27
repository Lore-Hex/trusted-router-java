package com.trustedrouter.receipts;

/** The receipt issuer is invalid or does not match the caller's pin. */
public final class ReceiptIssuerException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    public ReceiptIssuerException(String message) { super(message); }

    public ReceiptIssuerException(String message, Throwable cause) { super(message, cause); }
}
