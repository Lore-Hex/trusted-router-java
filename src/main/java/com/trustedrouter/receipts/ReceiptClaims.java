package com.trustedrouter.receipts;

/** Verified inference receipt v1 claims. */
public final class ReceiptClaims {
    /** Whether this SDK verified the receipt's embedded key attestation. */
    public enum AttestationStatus {
        /**
         * The verified.
         */
        VERIFIED,
        /**
         * The unverified by this sdk.
         */
        UNVERIFIED_BY_THIS_SDK
    }

    private final int receiptVersion;
    private final String issuer;
    private final long issuedAt;
    private final String id;
    private final String generationId;
    private final String nonce;
    private final String route;
    private final ReceiptHashClaims request;
    private final ReceiptHashClaims response;
    private final ReceiptModelClaims model;
    private final ReceiptUpstreamClaims upstream;
    private final String attestationSha256;
    private final AttestationStatus attestationStatus;

    ReceiptClaims(
            int receiptVersion,
            String issuer,
            long issuedAt,
            String id,
            String generationId,
            String nonce,
            String route,
            ReceiptHashClaims request,
            ReceiptHashClaims response,
            ReceiptModelClaims model,
            ReceiptUpstreamClaims upstream,
            String attestationSha256,
            AttestationStatus attestationStatus) {
        this.receiptVersion = receiptVersion;
        this.issuer = issuer;
        this.issuedAt = issuedAt;
        this.id = id;
        this.generationId = generationId;
        this.nonce = nonce;
        this.route = route;
        this.request = request;
        this.response = response;
        this.model = model;
        this.upstream = upstream;
        this.attestationSha256 = attestationSha256;
        this.attestationStatus = attestationStatus;
    }

    /**
     * Returns receipt version.
     *
     * @return the receipt version
     */
    public int getReceiptVersion() { return receiptVersion; }
    /**
     * Returns rv.
     *
     * @return the rv
     */
    public int getRv() { return receiptVersion; }
    /**
     * Returns issuer.
     *
     * @return the issuer
     */
    public String getIssuer() { return issuer; }
    /**
     * Returns iss.
     *
     * @return the iss
     */
    public String getIss() { return issuer; }
    /**
     * Returns issued at.
     *
     * @return the issued at
     */
    public long getIssuedAt() { return issuedAt; }
    /**
     * Returns iat.
     *
     * @return the iat
     */
    public long getIat() { return issuedAt; }
    /**
     * Returns id.
     *
     * @return the id
     */
    public String getId() { return id; }
    /**
     * Returns jti.
     *
     * @return the jti
     */
    public String getJti() { return id; }
    /**
     * Returns generation id.
     *
     * @return the generation id
     */
    public String getGenerationId() { return generationId; }
    /**
     * Returns gen.
     *
     * @return the gen
     */
    public String getGen() { return generationId; }
    /**
     * Returns nonce.
     *
     * @return the nonce
     */
    public String getNonce() { return nonce; }
    /**
     * Returns route.
     *
     * @return the route
     */
    public String getRoute() { return route; }
    /**
     * Returns request.
     *
     * @return the request
     */
    public ReceiptHashClaims getRequest() { return request; }
    /**
     * Returns req.
     *
     * @return the req
     */
    public ReceiptHashClaims getReq() { return request; }
    /**
     * Returns response.
     *
     * @return the response
     */
    public ReceiptHashClaims getResponse() { return response; }
    /**
     * Returns resp.
     *
     * @return the resp
     */
    public ReceiptHashClaims getResp() { return response; }
    /**
     * Returns model.
     *
     * @return the model
     */
    public ReceiptModelClaims getModel() { return model; }
    /**
     * Returns upstream.
     *
     * @return the upstream
     */
    public ReceiptUpstreamClaims getUpstream() { return upstream; }
    /**
     * Returns attestation sha256.
     *
     * @return the attestation sha256
     */
    public String getAttestationSha256() { return attestationSha256; }
    /**
     * Returns att sha256.
     *
     * @return the att sha256
     */
    public String getAttSha256() { return attestationSha256; }
    /**
     * Returns attestation status.
     *
     * @return the attestation status
     */
    public AttestationStatus getAttestationStatus() { return attestationStatus; }
    /**
     * Returns attestation.
     *
     * @return the attestation
     */
    public AttestationStatus getAttestation() { return attestationStatus; }
}
