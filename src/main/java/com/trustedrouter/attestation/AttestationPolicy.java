package com.trustedrouter.attestation;

import com.trustedrouter.models.TrustRelease;

/** Pinned workload values that a gateway attestation must match. */
public final class AttestationPolicy {
    private final String audience;
    private final String expectedCertSha256;
    private final String expectedImageDigest;
    private final String expectedImageReference;
    private final boolean allowDebug;

    private AttestationPolicy(Builder b) {
        audience = b.audience == null ? "quill-cloud" : b.audience;
        expectedCertSha256 = b.expectedCertSha256;
        expectedImageDigest = b.expectedImageDigest;
        expectedImageReference = b.expectedImageReference;
        allowDebug = b.allowDebug;
    }
    public static Builder builder() { return new Builder(); }
    public static AttestationPolicy fromTrustRelease(TrustRelease release) {
        if (release == null) { throw new NullPointerException("release"); }
        return builder().expectedImageDigest(release.getImageDigest())
                .expectedImageReference(release.getImageReference()).build();
    }
    public String getAudience() { return audience; }
    public String getExpectedCertSha256() { return expectedCertSha256; }
    public String getExpectedImageDigest() { return expectedImageDigest; }
    public String getExpectedImageReference() { return expectedImageReference; }
    public boolean isDebugAllowed() { return allowDebug; }
    public static final class Builder {
        private String audience;
        private String expectedCertSha256;
        private String expectedImageDigest;
        private String expectedImageReference;
        private boolean allowDebug;
        private Builder() {}
        public Builder audience(String value) { audience = value; return this; }
        public Builder expectedCertSha256(String value) { expectedCertSha256 = value; return this; }
        public Builder expectedImageDigest(String value) { expectedImageDigest = value; return this; }
        public Builder expectedImageReference(String value) { expectedImageReference = value; return this; }
        /** Debug Confidential Space images are rejected unless explicitly allowed for development. */
        public Builder allowDebug(boolean value) { allowDebug = value; return this; }
        public AttestationPolicy build() { return new AttestationPolicy(this); }
    }
}
