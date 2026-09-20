package com.trustedrouter.receipts;

/** The receipt issue time or requested age bound is invalid. */
public final class ReceiptTimeException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptTimeException.
     *
     * @param message the message
     */
    public ReceiptTimeException(String message) { super(message); }
    /**
     * Creates a ReceiptTimeException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptTimeException(String message, Throwable cause) { super(message, cause); }
}
