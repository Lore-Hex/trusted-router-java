package com.trustedrouter.models;

/** Stripe, PayPal, or stablecoin checkout session. */
public final class CheckoutResponse extends JsonModel {
    private String url;
    private String status;
    private String id;
    /**
     * Returns url.
     *
     * @return the url
     */
    public String getUrl() { return url; }
    /**
     * Returns status.
     *
     * @return the status
     */
    public String getStatus() { return status; }
    /**
     * Returns id.
     *
     * @return the id
     */
    public String getId() { return id; }
}
