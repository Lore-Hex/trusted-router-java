package com.trustedrouter.receipts;

/** The receipt issue time or requested age bound is invalid. */
public final class ReceiptTimeException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    public ReceiptTimeException(String message) { super(message); }
    public ReceiptTimeException(String message, Throwable cause) { super(message, cause); }
}
