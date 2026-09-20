package com.trustedrouter.receipts;

/** Validated upstream-verification claims from an inference receipt. */
public final class ReceiptUpstreamClaims {
    private final String tier;
    private final String policy;
    private final Long verifiedAt;
    private final Long verificationExpiresAt;
    private final String certificateSha256;

    ReceiptUpstreamClaims(
            String tier,
            String policy,
            Long verifiedAt,
            Long verificationExpiresAt,
            String certificateSha256) {
        this.tier = tier;
        this.policy = policy;
        this.verifiedAt = verifiedAt;
        this.verificationExpiresAt = verificationExpiresAt;
        this.certificateSha256 = certificateSha256;
    }

    /**
     * Returns tier.
     *
     * @return the tier
     */
    public String getTier() { return tier; }
    /**
     * Returns policy.
     *
     * @return the policy
     */
    public String getPolicy() { return policy; }
    /**
     * Returns verified at.
     *
     * @return the verified at
     */
    public Long getVerifiedAt() { return verifiedAt; }
    /**
     * Returns verification expires at.
     *
     * @return the verification expires at
     */
    public Long getVerificationExpiresAt() { return verificationExpiresAt; }
    /**
     * Returns certificate sha256.
     *
     * @return the certificate sha256
     */
    public String getCertificateSha256() { return certificateSha256; }
    /**
     * Returns cert sha256.
     *
     * @return the cert sha256
     */
    public String getCertSha256() { return certificateSha256; }
}
