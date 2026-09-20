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
    /**
     * Returns cert sha256.
     *
     * @return the cert sha256
     */
    public String getCertSha256() { return certSha256; }
    /**
     * Returns image digest.
     *
     * @return the image digest
     */
    public String getImageDigest() { return imageDigest; }
    /**
     * Returns image reference.
     *
     * @return the image reference
     */
    public String getImageReference() { return imageReference; }
    /**
     * Returns nonce.
     *
     * @return the nonce
     */
    public String getNonce() { return nonce; }
    /**
     * Returns expires at.
     *
     * @return the expires at
     */
    public Long getExpiresAt() { return expiresAt; }
    /**
     * Returns issuer.
     *
     * @return the issuer
     */
    public String getIssuer() { return issuer; }
    /**
     * Returns audience.
     *
     * @return the audience
     */
    public String getAudience() { return audience; }
    /**
     * Returns raw claims.
     *
     * @return the raw claims
     */
    public JsonObject getRawClaims() { return rawClaims.deepCopy(); }
}
