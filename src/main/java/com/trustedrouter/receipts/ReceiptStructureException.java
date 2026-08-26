package com.trustedrouter.receipts;

/** The compact or flattened JWS structure is malformed. */
public final class ReceiptStructureException extends ReceiptVerificationException {
    private static final long serialVersionUID = 1L;

    public ReceiptStructureException(String message) { super(message); }
    public ReceiptStructureException(String message, Throwable cause) { super(message, cause); }
}
