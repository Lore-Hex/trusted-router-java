package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.AttestationVerificationException;
import com.trustedrouter.attestation.AttestationVerificationOptions;
import com.trustedrouter.attestation.AttestationVerifier;
import com.trustedrouter.attestation.GatewayAttestation;
import com.trustedrouter.internal.JsonSupport;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AttestationVerifierTest {
    private KeyPair keyPair;
    private byte[] certificate;
    private String certificateHash;
    private JsonObject jwks;
    private JsonObject claims;

    @BeforeEach void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        certificate = "same-connection-leaf-der".getBytes(StandardCharsets.UTF_8);
        certificateHash = hex(MessageDigest.getInstance("SHA-256").digest(certificate));
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kid", "test-key");
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("n", unsignedBase64(publicKey.getModulus()));
        jwk.addProperty("e", unsignedBase64(publicKey.getPublicExponent()));
        JsonArray keys = new JsonArray();
        keys.add(jwk);
        jwks = new JsonObject();
        jwks.add("keys", keys);

        claims = new JsonObject();
        claims.addProperty("iss", AttestationVerifier.GCP_ISSUER);
        claims.addProperty("aud", "quill-cloud");
        claims.addProperty("exp", System.currentTimeMillis() / 1000L + 300L);
        claims.addProperty("dbgstat", "disabled-since-boot");
        claims.addProperty("swname", "CONFIDENTIAL_SPACE");
        claims.addProperty("secboot", true);
        claims.addProperty("hwmodel", "GCP_INTEL_TDX");
        claims.addProperty("tls_cert_sha256", certificateHash);
        JsonArray nonces = new JsonArray();
        nonces.add("fresh-nonce");
        claims.add("eat_nonce", nonces);
        JsonObject container = new JsonObject();
        container.addProperty("image_digest", "sha256:trusted");
        container.addProperty("image_reference", "us-docker.pkg.dev/project/image:release");
        JsonObject submods = new JsonObject();
        submods.add("container", container);
        claims.add("submods", submods);
    }

    @Test void verifiesSignatureImageNonceAndCertificateCommitment() throws Exception {
        AttestationPolicy policy = AttestationPolicy.builder()
                .expectedImageDigest("sha256:trusted")
                .expectedImageReference("us-docker.pkg.dev/project/image:release")
                .build();
        GatewayAttestation verified = AttestationVerifier.verify(jwt(claims),
                AttestationVerificationOptions.builder(policy)
                        .nonceHex("fresh-nonce")
                        .tlsCertificateDer(certificate)
                        .jwks(jwks)
                        .build());
        assertThat(verified.getCertSha256()).isEqualTo(certificateHash);
        assertThat(verified.getImageDigest()).isEqualTo("sha256:trusted");
        assertThat(verified.getNonce()).isEqualTo("fresh-nonce");
    }

    @Test void rejectsDebugWorkloadByDefault() throws Exception {
        claims.addProperty("dbgstat", "enabled");
        assertThatThrownBy(() -> verify(claims, AttestationPolicy.builder().build()))
                .isInstanceOf(AttestationVerificationException.class)
                .hasMessageContaining("debug");
    }

    @Test void acceptsAnyDigestAndReferenceInPublishedRolloutSet() throws Exception {
        AttestationPolicy policy = AttestationPolicy.builder()
                .expectedImageDigest("sha256:new")
                .expectedImageDigests(Arrays.asList("sha256:trusted", "sha256:new"))
                .expectedImageReference("us-docker.pkg.dev/project/image:new")
                .expectedImageReferences(Arrays.asList(
                        "us-docker.pkg.dev/project/image:release",
                        "us-docker.pkg.dev/project/image:new"))
                .build();
        GatewayAttestation verified = verify(claims, policy);
        assertThat(verified.getImageDigest()).isEqualTo("sha256:trusted");
    }

    @Test void rejectsMissingExpiryAndAmbiguousDebugState() throws Exception {
        JsonObject missingExpiry = claims.deepCopy();
        missingExpiry.remove("exp");
        assertThatThrownBy(() -> verify(missingExpiry, AttestationPolicy.builder().build()))
                .hasMessageContaining("expiration");

        JsonObject missingDebugState = claims.deepCopy();
        missingDebugState.remove("dbgstat");
        assertThatThrownBy(() -> verify(missingDebugState, AttestationPolicy.builder().build()))
                .hasMessageContaining("disabled-since-boot");

        JsonObject unknownDebugState = claims.deepCopy();
        unknownDebugState.addProperty("dbgstat", "unknown");
        assertThatThrownBy(() -> verify(unknownDebugState, AttestationPolicy.builder().build()))
                .hasMessageContaining("disabled-since-boot");
    }

    @Test void rejectsNonConfidentialOrInsecureRuntimeClaims() throws Exception {
        JsonObject wrongSoftware = claims.deepCopy();
        wrongSoftware.addProperty("swname", "GCE");
        assertThatThrownBy(() -> verify(wrongSoftware, AttestationPolicy.builder().build()))
                .hasMessageContaining("not running Confidential Space");

        JsonObject insecureBoot = claims.deepCopy();
        insecureBoot.addProperty("secboot", false);
        assertThatThrownBy(() -> verify(insecureBoot, AttestationPolicy.builder().build()))
                .hasMessageContaining("Secure Boot");

        JsonObject wrongHardware = claims.deepCopy();
        wrongHardware.addProperty("hwmodel", "GCP_SHIELDED_VM");
        assertThatThrownBy(() -> verify(wrongHardware, AttestationPolicy.builder().build()))
                .hasMessageContaining("hardware model");
    }

    @Test void rejectsWrongLengthTlsExporter() throws Exception {
        assertThatThrownBy(() -> AttestationVerifier.verify(jwt(claims),
                AttestationVerificationOptions.builder(AttestationPolicy.builder().build())
                        .nonceHex("fresh-nonce").tlsCertificateDer(certificate)
                        .tlsExporter(new byte[31]).jwks(jwks).build()))
                .hasMessageContaining("32 bytes");
    }

    @Test void explicitlyAllowsDebugOnlyForDevelopmentPolicy() throws Exception {
        claims.addProperty("dbgstat", "enabled");
        GatewayAttestation verified = verify(
                claims, AttestationPolicy.builder().allowDebug(true).build());
        assertThat(verified.getIssuer()).isEqualTo(AttestationVerifier.GCP_ISSUER);
    }

    @Test void rejectsExpiredWrongImageWrongNonceAndWrongCertificate() throws Exception {
        JsonObject expired = claims.deepCopy();
        expired.addProperty("exp", 1);
        assertThatThrownBy(() -> verify(expired, AttestationPolicy.builder().build()))
                .hasMessageContaining("expired");

        assertThatThrownBy(() -> verify(claims,
                AttestationPolicy.builder().expectedImageDigest("sha256:other").build()))
                .hasMessageContaining("image_digest mismatch");

        assertThatThrownBy(() -> AttestationVerifier.verify(jwt(claims),
                AttestationVerificationOptions.builder(AttestationPolicy.builder().build())
                        .nonceHex("replayed").tlsCertificateDer(certificate).jwks(jwks).build()))
                .hasMessageContaining("nonce");

        assertThatThrownBy(() -> AttestationVerifier.verify(jwt(claims),
                AttestationVerificationOptions.builder(AttestationPolicy.builder().build())
                        .nonceHex("fresh-nonce").tlsCertificateDer(new byte[] {1, 2, 3})
                        .jwks(jwks).build()))
                .hasMessageContaining("certificate");
    }

    @Test void rejectsTamperedSignatureAndUnsupportedAlgorithm() throws Exception {
        byte[] token = jwt(claims);
        int signatureStart = new String(token, StandardCharsets.US_ASCII).lastIndexOf('.') + 1;
        token[signatureStart] = token[signatureStart] == 'a' ? (byte) 'b' : (byte) 'a';
        assertThatThrownBy(() -> AttestationVerifier.verify(token, options()))
                .isInstanceOf(AttestationVerificationException.class);

        JsonObject header = new JsonObject();
        header.addProperty("alg", "none");
        header.addProperty("kid", "test-key");
        String unsigned = b64(JsonSupport.GSON.toJson(header).getBytes(StandardCharsets.UTF_8))
                + "." + b64(JsonSupport.GSON.toJson(claims).getBytes(StandardCharsets.UTF_8)) + ".AA";
        assertThatThrownBy(() -> AttestationVerifier.verify(
                unsigned.getBytes(StandardCharsets.US_ASCII), options()))
                .hasMessageContaining("expected RS256");
    }

    private GatewayAttestation verify(JsonObject value, AttestationPolicy policy) throws Exception {
        return AttestationVerifier.verify(jwt(value),
                AttestationVerificationOptions.builder(policy).nonceHex("fresh-nonce")
                        .tlsCertificateDer(certificate).jwks(jwks).build());
    }

    private AttestationVerificationOptions options() {
        return AttestationVerificationOptions.builder(AttestationPolicy.builder().build())
                .nonceHex("fresh-nonce").tlsCertificateDer(certificate).jwks(jwks).build();
    }

    private byte[] jwt(JsonObject payload) throws Exception {
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("kid", "test-key");
        String signingInput = b64(JsonSupport.GSON.toJson(header).getBytes(StandardCharsets.UTF_8))
                + "." + b64(JsonSupport.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return (signingInput + "." + b64(signer.sign())).getBytes(StandardCharsets.US_ASCII);
    }

    private static String unsignedBase64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return b64(bytes);
    }
    private static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) { text.append(String.format("%02x", item & 0xff)); }
        return text.toString();
    }
}
