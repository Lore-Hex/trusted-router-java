package com.trustedrouter.receipts;

import com.trustedrouter.attestation.AttestationVerificationOptions;

/** Exact request/response bytes and policy checks used to verify a receipt. */
public final class ReceiptVerificationOptions {
    private final String expectedIssuer;
    private final byte[] requestBody;
    private final byte[] responseBody;
    private final byte[] responseStream;
    private final String expectedNonce;
    private final Long maxAgeSeconds;
    private final Long now;
    private final byte[] attestationDocument;
    private final boolean requireAttestation;
    private final boolean requireBindings;
    private final AttestationVerificationOptions gcpAttestationOptions;

    private ReceiptVerificationOptions(Builder builder) {
        expectedIssuer = builder.expectedIssuer;
        requestBody = copy(builder.requestBody);
        responseBody = copy(builder.responseBody);
        responseStream = copy(builder.responseStream);
        expectedNonce = builder.expectedNonce;
        maxAgeSeconds = builder.maxAgeSeconds;
        now = builder.now;
        attestationDocument = copy(builder.attestationDocument);
        requireAttestation = builder.requireAttestation;
        requireBindings = builder.requireBindings;
        gcpAttestationOptions = builder.gcpAttestationOptions;
    }

    /**
     * Creates verification options pinned to the expected receipt issuer.
     *
     * @param expectedIssuer the expected issuer
     * @return a new builder
     */
    public static Builder builder(String expectedIssuer) { return new Builder(expectedIssuer); }

    /**
     * Sets to builder.
     *
     * @return this builder
     */
    public Builder toBuilder() {
        return new Builder(expectedIssuer)
                .requestBody(requestBody)
                .responseBody(responseBody)
                .responseStream(responseStream)
                .expectedNonce(expectedNonce)
                .maxAgeSeconds(maxAgeSeconds)
                .now(now)
                .attestationDocument(attestationDocument)
                .requireAttestation(requireAttestation)
                .requireBindings(requireBindings)
                .gcpAttestationOptions(gcpAttestationOptions);
    }

    String expectedIssuer() { return expectedIssuer; }
    byte[] requestBody() { return copy(requestBody); }
    byte[] responseBody() { return copy(responseBody); }
    byte[] responseStream() { return copy(responseStream); }
    String expectedNonce() { return expectedNonce; }
    Long maxAgeSeconds() { return maxAgeSeconds; }
    Long now() { return now; }
    byte[] attestationDocument() { return copy(attestationDocument); }
    boolean requireAttestation() { return requireAttestation; }
    boolean requireBindings() { return requireBindings; }
    AttestationVerificationOptions gcpAttestationOptions() { return gcpAttestationOptions; }

    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }

    /**
     * Builder for receipt verification inputs. Traffic bindings and attestation verification
     * default to required.
     */
    public static final class Builder {
        private final String expectedIssuer;
        private byte[] requestBody;
        private byte[] responseBody;
        private byte[] responseStream;
        private String expectedNonce;
        private Long maxAgeSeconds;
        private Long now;
        private byte[] attestationDocument;
        private boolean requireAttestation = true;
        private boolean requireBindings = true;
        private AttestationVerificationOptions gcpAttestationOptions;

        private Builder(String expectedIssuer) { this.expectedIssuer = expectedIssuer; }

        /**
         * Sets request body.
         *
         * @param value the request body
         * @return this builder
         */
        public Builder requestBody(byte[] value) { requestBody = copy(value); return this; }
        /**
         * Sets response body.
         *
         * @param value the response body
         * @return this builder
         */
        public Builder responseBody(byte[] value) { responseBody = copy(value); return this; }
        /**
         * Sets response stream.
         *
         * @param value the response stream
         * @return this builder
         */
        public Builder responseStream(byte[] value) { responseStream = copy(value); return this; }
        /**
         * Sets expected nonce.
         *
         * @param value the expected nonce
         * @return this builder
         */
        public Builder expectedNonce(String value) { expectedNonce = value; return this; }
        /**
         * Sets max age seconds.
         *
         * @param value the max age seconds
         * @return this builder
         */
        public Builder maxAgeSeconds(long value) { maxAgeSeconds = Long.valueOf(value); return this; }
        Builder maxAgeSeconds(Long value) { maxAgeSeconds = value; return this; }
        /**
         * Sets now.
         *
         * @param value the now
         * @return this builder
         */
        public Builder now(long value) { now = Long.valueOf(value); return this; }
        Builder now(Long value) { now = value; return this; }

        /**
         * Supplies the exact GCP attestation JWT bytes pinned by a compact receipt's
         * {@code att_sha256} claim. For a flattened receipt, the supplied bytes must equal its
         * embedded document.
         *
         * @param value the attestation document
         * @return this builder
         */
        public Builder attestationDocument(byte[] value) {
            attestationDocument = copy(value);
            return this;
        }

        /**
         * Sets require attestation.
         *
         * @param value the require attestation
         * @return this builder
         */
        public Builder requireAttestation(boolean value) { requireAttestation = value; return this; }

        /**
         * Requires exact request and response traffic by default. Set false only for deliberate
         * signature-only or partial-binding inspection.
         *
         * @param value the require bindings
         * @return this builder
         */
        public Builder requireBindings(boolean value) { requireBindings = value; return this; }

        /**
         * Supplies release pins and optionally pre-fetched GCP JWKS for offline attestation checks.
         * When omitted, the verifier fetches the public trust release and the GCP JWKS.
         *
         * @param value the gcp attestation options
         * @return this builder
         */
        public Builder gcpAttestationOptions(AttestationVerificationOptions value) {
            gcpAttestationOptions = value;
            return this;
        }

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public ReceiptVerificationOptions build() {
            return new ReceiptVerificationOptions(this);
        }
    }
}
