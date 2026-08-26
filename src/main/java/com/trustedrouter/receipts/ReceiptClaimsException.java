package com.trustedrouter.receipts;

/** A required receipt claim is missing, malformed, or unsupported. */
public class ReceiptClaimsException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptClaimsException(String message) { super(message); }
    public ReceiptClaimsException(String message, Throwable cause) { super(message, cause); }
}
