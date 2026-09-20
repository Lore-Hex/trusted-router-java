package com.trustedrouter.receipts;

/** A request or response byte digest check failed. */
public final class ReceiptHashException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptHashException.
     *
     * @param message the message
     */
    public ReceiptHashException(String message) { super(message); }
    /**
     * Creates a ReceiptHashException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptHashException(String message, Throwable cause) { super(message, cause); }
}
