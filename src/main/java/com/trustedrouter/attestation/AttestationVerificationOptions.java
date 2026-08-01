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
    public static Builder builder(AttestationPolicy policy) { return new Builder(policy); }
    AttestationPolicy policy() { return policy; }
    String nonceHex() { return nonceHex; }
    byte[] tlsCertificateDer() { return copy(tlsCertificateDer); }
    byte[] tlsExporter() { return copy(tlsExporter); }
    JsonObject jwks() { return jwks == null ? null : jwks.deepCopy(); }
    String jwksUrl() { return jwksUrl; }
    OkHttpClient httpClient() { return httpClient; }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    public static final class Builder {
        private final AttestationPolicy policy;
        private String nonceHex;
        private byte[] tlsCertificateDer;
        private byte[] tlsExporter;
        private JsonObject jwks;
        private String jwksUrl;
        private OkHttpClient httpClient;
        private Builder(AttestationPolicy policy) { this.policy = policy; }
        public Builder nonceHex(String value) { nonceHex = value; return this; }
        public Builder tlsCertificateDer(byte[] value) { tlsCertificateDer = copy(value); return this; }
        public Builder tlsExporter(byte[] value) { tlsExporter = copy(value); return this; }
        public Builder jwks(JsonObject value) { jwks = value; return this; }
        public Builder jwksUrl(String value) { jwksUrl = value; return this; }
        public Builder httpClient(OkHttpClient value) { httpClient = value; return this; }
        public AttestationVerificationOptions build() { return new AttestationVerificationOptions(this); }
    }
}
