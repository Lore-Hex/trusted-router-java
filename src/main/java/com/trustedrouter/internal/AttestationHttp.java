package com.trustedrouter.internal;

import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.AttestationVerificationOptions;
import com.trustedrouter.attestation.AttestationVerifier;
import com.trustedrouter.attestation.GatewayAttestation;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * L10 attestation glue: nonce generation, {@code /v1}-root trimming, and
 * TLS-leaf capture from the exact OkHttp handshake that returned the JWT.
 * Internal class with no compatibility guarantees.
 *
 * <p>Fetches go through the engine's absolute path (singleton candidate
 * list, no credentials), so they can never fail over to an alias domain.
 *
 * <p>Known, flagged transport BYPASS: {@code AttestationVerifier.fetchJwks}
 * performs its own single-shot OkHttp call against the Google JWKS endpoint
 * (a documented credential-free metadata fetch outside the engine by
 * design). Do not give it retries here.
 */
public final class AttestationHttp {
    private AttestationHttp() {}

    /** Generates a random 16-byte nonce as lowercase hex. */
    public static String randomNonceHex() {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        return hex(nonceBytes);
    }

    /** Builds the attestation URL from the API base, trimming a /v1 suffix. */
    public static String attestationUrl(String baseUrl, String nonceHex) {
        String root = baseUrl;
        if (root.endsWith("/v1")) {
            root = root.substring(0, root.length() - 3);
        }
        String url = root + "/attestation";
        if (nonceHex != null && !nonceHex.isEmpty()) {
            url += "?nonce=" + encode(nonceHex);
        }
        return url;
    }

    /** Fetches the raw attestation document bytes. */
    public static byte[] fetchAttestation(Transport transport, String baseUrl, String nonceHex)
            throws TrustedRouterException {
        Response response = transport.executeAbsolute(
                attestationUrl(baseUrl, nonceHex), "GET", false);
        try {
            Transport.requireSuccess(response);
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } catch (IOException error) {
            throw new InternalException(503, error.getMessage(), null, error);
        } finally {
            response.close();
        }
    }

    /**
     * Fetches a fresh attestation and verifies it against the TLS leaf
     * certificate from the exact OkHttp connection that returned the JWT.
     */
    public static GatewayAttestation verifyGatewayAttestation(
            Transport transport, String baseUrl, AttestationPolicy policy, String nonceHex)
            throws TrustedRouterException, GeneralSecurityException {
        Response response = transport.executeAbsolute(
                attestationUrl(baseUrl, nonceHex), "GET", false);
        try {
            Transport.requireSuccess(response);
            if (response.handshake() == null || response.handshake().peerCertificates().isEmpty()) {
                throw new GeneralSecurityException("attestation response had no TLS peer certificate");
            }
            Certificate certificate = response.handshake().peerCertificates().get(0);
            ResponseBody body = response.body();
            if (body == null) {
                throw new GeneralSecurityException("attestation response was empty");
            }
            return AttestationVerifier.verify(body.bytes(),
                    AttestationVerificationOptions.builder(policy)
                            .nonceHex(nonceHex)
                            .tlsCertificateDer(certificate.getEncoded())
                            .build());
        } catch (IOException error) {
            throw new InternalException(503, error.getMessage(), null, error);
        } finally {
            response.close();
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 unavailable", impossible);
        }
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int item = value[i] & 0xff;
            output[i * 2] = digits[item >>> 4];
            output[i * 2 + 1] = digits[item & 0x0f];
        }
        return new String(output);
    }
}
