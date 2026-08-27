package com.trustedrouter.receipts;

/** Required caller traffic for a receipt digest binding is absent. */
public final class MissingBindingException extends ReceiptClaimsException {
    private static final long serialVersionUID = 1L;

    public MissingBindingException(String message) { super(message); }
}
