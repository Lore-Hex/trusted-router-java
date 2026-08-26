package com.trustedrouter.receipts;

import com.trustedrouter.attestation.AttestationVerificationOptions;

/** Exact request/response bytes and policy checks used to verify a receipt. */
public final class ReceiptVerificationOptions {
    private final byte[] requestBody;
    private final byte[] responseBody;
    private final byte[] responseStream;
    private final String expectedNonce;
    private final Long maxAgeSeconds;
    private final Long now;
    private final byte[] attestationDocument;
    private final boolean requireAttestation;
    private final AttestationVerificationOptions gcpAttestationOptions;

    private ReceiptVerificationOptions(Builder builder) {
        requestBody = copy(builder.requestBody);
        responseBody = copy(builder.responseBody);
        responseStream = copy(builder.responseStream);
        expectedNonce = builder.expectedNonce;
        maxAgeSeconds = builder.maxAgeSeconds;
        now = builder.now;
        attestationDocument = copy(builder.attestationDocument);
        requireAttestation = builder.requireAttestation;
        gcpAttestationOptions = builder.gcpAttestationOptions;
    }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        return new Builder()
                .requestBody(requestBody)
                .responseBody(responseBody)
                .responseStream(responseStream)
                .expectedNonce(expectedNonce)
                .maxAgeSeconds(maxAgeSeconds)
                .now(now)
                .attestationDocument(attestationDocument)
                .requireAttestation(requireAttestation)
                .gcpAttestationOptions(gcpAttestationOptions);
    }

    byte[] requestBody() { return copy(requestBody); }
    byte[] responseBody() { return copy(responseBody); }
    byte[] responseStream() { return copy(responseStream); }
    String expectedNonce() { return expectedNonce; }
    Long maxAgeSeconds() { return maxAgeSeconds; }
    Long now() { return now; }
    byte[] attestationDocument() { return copy(attestationDocument); }
    boolean requireAttestation() { return requireAttestation; }
    AttestationVerificationOptions gcpAttestationOptions() { return gcpAttestationOptions; }

    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }

    /** Builder for receipt verification inputs. Attestation verification defaults to required. */
    public static final class Builder {
        private byte[] requestBody;
        private byte[] responseBody;
        private byte[] responseStream;
        private String expectedNonce;
        private Long maxAgeSeconds;
        private Long now;
        private byte[] attestationDocument;
        private boolean requireAttestation = true;
        private AttestationVerificationOptions gcpAttestationOptions;

        private Builder() {}

        public Builder requestBody(byte[] value) { requestBody = copy(value); return this; }
        public Builder responseBody(byte[] value) { responseBody = copy(value); return this; }
        public Builder responseStream(byte[] value) { responseStream = copy(value); return this; }
        public Builder expectedNonce(String value) { expectedNonce = value; return this; }
        public Builder maxAgeSeconds(long value) { maxAgeSeconds = Long.valueOf(value); return this; }
        Builder maxAgeSeconds(Long value) { maxAgeSeconds = value; return this; }
        public Builder now(long value) { now = Long.valueOf(value); return this; }
        Builder now(Long value) { now = value; return this; }

        /**
         * Supplies the exact GCP attestation JWT bytes pinned by a compact receipt's
         * {@code att_sha256} claim. For a flattened receipt, the supplied bytes must equal its
         * embedded document.
         */
        public Builder attestationDocument(byte[] value) {
            attestationDocument = copy(value);
            return this;
        }

        public Builder requireAttestation(boolean value) { requireAttestation = value; return this; }

        /**
         * Supplies release pins and optionally pre-fetched GCP JWKS for offline attestation checks.
         * When omitted, the verifier fetches the public trust release and the GCP JWKS.
         */
        public Builder gcpAttestationOptions(AttestationVerificationOptions value) {
            gcpAttestationOptions = value;
            return this;
        }

        public ReceiptVerificationOptions build() {
            return new ReceiptVerificationOptions(this);
        }
    }
}
