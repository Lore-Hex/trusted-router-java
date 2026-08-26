package com.trustedrouter.receipts;

/** The protected JWS header is invalid or unsupported. */
public final class ReceiptHeaderException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptHeaderException(String message) { super(message); }
    public ReceiptHeaderException(String message, Throwable cause) { super(message, cause); }
}
