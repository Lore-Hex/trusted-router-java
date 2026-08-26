package com.trustedrouter.receipts;

/** Validated model-routing claims from an inference receipt. */
public final class ReceiptModelClaims {
    private final String requested;
    private final String selected;
    private final String provider;
    private final String endpoint;

    ReceiptModelClaims(String requested, String selected, String provider, String endpoint) {
        this.requested = requested;
        this.selected = selected;
        this.provider = provider;
        this.endpoint = endpoint;
    }

    public String getRequested() { return requested; }
    public String getSelected() { return selected; }
    public String getProvider() { return provider; }
    public String getEndpoint() { return endpoint; }
}
