package com.trustedrouter.receipts;

/** Verified inference receipt v1 claims. */
public final class ReceiptClaims {
    /** Whether this SDK verified the receipt's embedded key attestation. */
    public enum AttestationStatus {
        VERIFIED,
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

    public int getReceiptVersion() { return receiptVersion; }
    public int getRv() { return receiptVersion; }
    public String getIssuer() { return issuer; }
    public String getIss() { return issuer; }
    public long getIssuedAt() { return issuedAt; }
    public long getIat() { return issuedAt; }
    public String getId() { return id; }
    public String getJti() { return id; }
    public String getGenerationId() { return generationId; }
    public String getGen() { return generationId; }
    public String getNonce() { return nonce; }
    public String getRoute() { return route; }
    public ReceiptHashClaims getRequest() { return request; }
    public ReceiptHashClaims getReq() { return request; }
    public ReceiptHashClaims getResponse() { return response; }
    public ReceiptHashClaims getResp() { return response; }
    public ReceiptModelClaims getModel() { return model; }
    public ReceiptUpstreamClaims getUpstream() { return upstream; }
    public String getAttestationSha256() { return attestationSha256; }
    public String getAttSha256() { return attestationSha256; }
    public AttestationStatus getAttestationStatus() { return attestationStatus; }
    public AttestationStatus getAttestation() { return attestationStatus; }
}
