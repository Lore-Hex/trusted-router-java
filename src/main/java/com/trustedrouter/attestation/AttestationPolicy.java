package com.trustedrouter.attestation;

import com.trustedrouter.models.TrustRelease;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pinned workload values that a gateway attestation must match. */
public final class AttestationPolicy {
    private final String audience;
    private final String expectedCertSha256;
    private final String expectedImageDigest;
    private final List<String> expectedImageDigests;
    private final String expectedImageReference;
    private final List<String> expectedImageReferences;
    private final boolean allowDebug;

    private AttestationPolicy(Builder b) {
        audience = b.audience == null ? "quill-cloud" : b.audience;
        expectedCertSha256 = b.expectedCertSha256;
        expectedImageDigest = b.expectedImageDigest;
        if (b.expectedImageDigests != null && !b.expectedImageDigests.isEmpty()) {
            expectedImageDigests = Collections.unmodifiableList(
                    new ArrayList<String>(b.expectedImageDigests));
        } else if (expectedImageDigest != null && !expectedImageDigest.isEmpty()) {
            expectedImageDigests = Collections.singletonList(expectedImageDigest);
        } else {
            expectedImageDigests = Collections.emptyList();
        }
        expectedImageReference = b.expectedImageReference;
        if (b.expectedImageReferences != null && !b.expectedImageReferences.isEmpty()) {
            expectedImageReferences = Collections.unmodifiableList(
                    new ArrayList<String>(b.expectedImageReferences));
        } else if (expectedImageReference != null && !expectedImageReference.isEmpty()) {
            expectedImageReferences = Collections.singletonList(expectedImageReference);
        } else {
            expectedImageReferences = Collections.emptyList();
        }
        allowDebug = b.allowDebug;
    }
    public static Builder builder() { return new Builder(); }
    public static AttestationPolicy fromTrustRelease(TrustRelease release) {
        if (release == null) { throw new NullPointerException("release"); }
        AttestationPolicy policy = builder().expectedImageDigest(release.getImageDigest())
                .expectedImageDigests(release.getAcceptedImageDigests())
                .expectedImageReference(release.getImageReference())
                .expectedImageReferences(release.getAcceptedImageReferences()).build();
        if (!policy.pinsImageIdentity()) {
            // A truncated body, an error page that happens to parse as JSON, or
            // a schema change all land here. Returning the policy anyway would
            // leave the caller believing it verified a specific build while
            // both image checks silently no-op, so refuse where the degraded
            // input is still visible.
            throw new IllegalArgumentException(
                    "trust release pins no image identity (none of image_digest, "
                    + "accepted_image_digests, image_reference, accepted_image_references); "
                    + "refusing to build a policy that would accept any "
                    + "Confidential Space workload");
        }
        return policy;
    }

    /**
     * Whether this policy constrains <em>which</em> workload image is acceptable.
     *
     * <p>Both image checks in the verifier go through {@code requireOneOf}, which
     * returns immediately on an empty accepted list, so a policy pinning neither
     * a digest nor a reference accepts any genuinely-attested Confidential Space
     * workload &mdash; it proves "some CSP VM" rather than "the gateway build we
     * published". Policy construction and verification both refuse that state
     * rather than silently downgrading the guarantee.
     *
     * @return true when the policy pins at least one image digest or reference
     */
    public boolean pinsImageIdentity() {
        return !expectedImageDigests.isEmpty()
                || (expectedImageDigest != null && !expectedImageDigest.isEmpty())
                || !expectedImageReferences.isEmpty()
                || (expectedImageReference != null && !expectedImageReference.isEmpty());
    }
    public String getAudience() { return audience; }
    public String getExpectedCertSha256() { return expectedCertSha256; }
    public String getExpectedImageDigest() { return expectedImageDigest; }
    public List<String> getExpectedImageDigests() { return expectedImageDigests; }
    public String getExpectedImageReference() { return expectedImageReference; }
    public List<String> getExpectedImageReferences() { return expectedImageReferences; }
    public boolean isDebugAllowed() { return allowDebug; }
    public static final class Builder {
        private String audience;
        private String expectedCertSha256;
        private String expectedImageDigest;
        private List<String> expectedImageDigests;
        private String expectedImageReference;
        private List<String> expectedImageReferences;
        private boolean allowDebug;
        private Builder() {}
        public Builder audience(String value) { audience = value; return this; }
        public Builder expectedCertSha256(String value) { expectedCertSha256 = value; return this; }
        public Builder expectedImageDigest(String value) { expectedImageDigest = value; return this; }
        public Builder expectedImageDigests(List<String> values) {
            expectedImageDigests = values;
            return this;
        }
        public Builder expectedImageReference(String value) { expectedImageReference = value; return this; }
        public Builder expectedImageReferences(List<String> values) {
            expectedImageReferences = values;
            return this;
        }
        /** Debug Confidential Space images are rejected unless explicitly allowed for development. */
        public Builder allowDebug(boolean value) { allowDebug = value; return this; }
        public AttestationPolicy build() { return new AttestationPolicy(this); }
    }
}
