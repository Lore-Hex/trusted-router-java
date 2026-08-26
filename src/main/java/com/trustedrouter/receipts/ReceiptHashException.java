package com.trustedrouter.receipts;

/** A request or response byte digest check failed. */
public final class ReceiptHashException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptHashException(String message) { super(message); }
    public ReceiptHashException(String message, Throwable cause) { super(message, cause); }
}
