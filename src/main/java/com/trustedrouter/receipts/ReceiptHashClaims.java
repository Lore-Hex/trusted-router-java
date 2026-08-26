package com.trustedrouter.receipts;

/** A validated receipt hash record. */
public final class ReceiptHashClaims {
    private final String algorithm;
    private final String hash;
    private final String of;
    private final Long events;

    ReceiptHashClaims(String algorithm, String hash, String of, Long events) {
        this.algorithm = algorithm;
        this.hash = hash;
        this.of = of;
        this.events = events;
    }

    public String getAlgorithm() { return algorithm; }
    public String getAlg() { return algorithm; }
    public String getHash() { return hash; }
    public String getOf() { return of; }
    public Long getEvents() { return events; }
}
