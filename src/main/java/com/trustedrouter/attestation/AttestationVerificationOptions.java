package com.trustedrouter.attestation;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;

/** Inputs for offline or live attestation verification. */
public final class AttestationVerificationOptions {
    private final AttestationPolicy policy;
    private final String nonceHex;
    private final byte[] tlsCertificateDer;
    private final byte[] tlsExporter;
    private final JsonObject jwks;
    private final String jwksUrl;
    private final OkHttpClient httpClient;

    private AttestationVerificationOptions(Builder b) {
        if (b.policy == null) { throw new IllegalStateException("policy is required"); }
        policy = b.policy;
        nonceHex = b.nonceHex;
        tlsCertificateDer = copy(b.tlsCertificateDer);
        tlsExporter = copy(b.tlsExporter);
        jwks = b.jwks == null ? null : b.jwks.deepCopy();
        jwksUrl = b.jwksUrl;
        httpClient = b.httpClient;
    }
    /**
     * Creates a builder for this value.
     *
     * @param policy the policy
     * @return a new builder
     */
    public static Builder builder(AttestationPolicy policy) { return new Builder(policy); }
    AttestationPolicy policy() { return policy; }
    String nonceHex() { return nonceHex; }
    byte[] tlsCertificateDer() { return copy(tlsCertificateDer); }
    byte[] tlsExporter() { return copy(tlsExporter); }
    JsonObject jwks() { return jwks == null ? null : jwks.deepCopy(); }
    String jwksUrl() { return jwksUrl; }
    OkHttpClient httpClient() { return httpClient; }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    /**
     * Represents builder.
     */
    public static final class Builder {
        private final AttestationPolicy policy;
        private String nonceHex;
        private byte[] tlsCertificateDer;
        private byte[] tlsExporter;
        private JsonObject jwks;
        private String jwksUrl;
        private OkHttpClient httpClient;
        private Builder(AttestationPolicy policy) { this.policy = policy; }
        /**
         * Sets nonce hex.
         *
         * @param value the nonce hex
         * @return this builder
         */
        public Builder nonceHex(String value) { nonceHex = value; return this; }
        /**
         * Sets tls certificate der.
         *
         * @param value the tls certificate der
         * @return this builder
         */
        public Builder tlsCertificateDer(byte[] value) { tlsCertificateDer = copy(value); return this; }
        /**
         * Sets tls exporter.
         *
         * @param value the tls exporter
         * @return this builder
         */
        public Builder tlsExporter(byte[] value) { tlsExporter = copy(value); return this; }
        /**
         * Sets jwks.
         *
         * @param value the jwks
         * @return this builder
         */
        public Builder jwks(JsonObject value) { jwks = value; return this; }
        /**
         * Sets jwks url.
         *
         * @param value the jwks url
         * @return this builder
         */
        public Builder jwksUrl(String value) { jwksUrl = value; return this; }
        /**
         * Sets http client.
         *
         * @param value the http client
         * @return this builder
         */
        public Builder httpClient(OkHttpClient value) { httpClient = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public AttestationVerificationOptions build() { return new AttestationVerificationOptions(this); }
    }
}
