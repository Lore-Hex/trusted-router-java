package com.trustedrouter.receipts;

/** Required caller traffic for a receipt digest binding is absent. */
public final class MissingBindingException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a MissingBindingException.
     *
     * @param message the message
     */
    public MissingBindingException(String message) { super(message); }
}
