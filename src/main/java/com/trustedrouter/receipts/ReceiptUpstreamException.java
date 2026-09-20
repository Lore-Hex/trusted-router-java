package com.trustedrouter.receipts;

/** The upstream verification window or tier claims are invalid. */
public final class ReceiptUpstreamException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a ReceiptUpstreamException.
     *
     * @param message the message
     */
    public ReceiptUpstreamException(String message) { super(message); }
}
