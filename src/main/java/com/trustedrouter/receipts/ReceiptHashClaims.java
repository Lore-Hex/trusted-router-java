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

    /**
     * Returns algorithm.
     *
     * @return the algorithm
     */
    public String getAlgorithm() { return algorithm; }
    /**
     * Returns alg.
     *
     * @return the alg
     */
    public String getAlg() { return algorithm; }
    /**
     * Returns hash.
     *
     * @return the hash
     */
    public String getHash() { return hash; }
    /**
     * Returns of.
     *
     * @return the of
     */
    public String getOf() { return of; }
    /**
     * Returns events.
     *
     * @return the events
     */
    public Long getEvents() { return events; }
}
