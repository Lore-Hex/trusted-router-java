package com.trustedrouter.models;

/** Stripe, PayPal, or stablecoin checkout session. */
public final class CheckoutResponse extends JsonModel {
    private String url;
    private String status;
    private String id;
    public String getUrl() { return url; }
    public String getStatus() { return status; }
    public String getId() { return id; }
}
