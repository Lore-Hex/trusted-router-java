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

    public String getTier() { return tier; }
    public String getPolicy() { return policy; }
    public Long getVerifiedAt() { return verifiedAt; }
    public Long getVerificationExpiresAt() { return verificationExpiresAt; }
    public String getCertificateSha256() { return certificateSha256; }
    public String getCertSha256() { return certificateSha256; }
}
