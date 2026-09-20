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

    /**
     * Returns requested.
     *
     * @return the requested
     */
    public String getRequested() { return requested; }
    /**
     * Returns selected.
     *
     * @return the selected
     */
    public String getSelected() { return selected; }
    /**
     * Returns provider.
     *
     * @return the provider
     */
    public String getProvider() { return provider; }
    /**
     * Returns endpoint.
     *
     * @return the endpoint
     */
    public String getEndpoint() { return endpoint; }
}
