package com.trustedrouter.attestation;

import com.google.gson.JsonObject;

/** Verified GCP Confidential Space gateway identity. */
public final class GatewayAttestation {
    private final String certSha256;
    private final String imageDigest;
    private final String imageReference;
    private final String nonce;
    private final Long expiresAt;
    private final String issuer;
    private final String audience;
    private final JsonObject rawClaims;

    GatewayAttestation(
            String certSha256, String imageDigest, String imageReference, String nonce,
            Long expiresAt, String issuer, String audience, JsonObject rawClaims) {
        this.certSha256 = certSha256;
        this.imageDigest = imageDigest;
        this.imageReference = imageReference;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
        this.issuer = issuer;
        this.audience = audience;
        this.rawClaims = rawClaims.deepCopy();
    }
    public String getCertSha256() { return certSha256; }
    public String getImageDigest() { return imageDigest; }
    public String getImageReference() { return imageReference; }
    public String getNonce() { return nonce; }
    public Long getExpiresAt() { return expiresAt; }
    public String getIssuer() { return issuer; }
    public String getAudience() { return audience; }
    public JsonObject getRawClaims() { return rawClaims.deepCopy(); }
}
