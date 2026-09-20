package com.trustedrouter.receipts;

/** The protected JWS header is invalid or unsupported. */
public final class ReceiptHeaderException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptHeaderException.
     *
     * @param message the message
     */
    public ReceiptHeaderException(String message) { super(message); }
    /**
     * Creates a ReceiptHeaderException.
     *
     * @param message the message
     * @param cause the cause
     */
    public ReceiptHeaderException(String message, Throwable cause) { super(message, cause); }
}
