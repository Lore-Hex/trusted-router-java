package com.trustedrouter.attestation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.internal.JsonSupport;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Verifies Google-signed Confidential Space JWTs and all TrustedRouter pins. */
public final class AttestationVerifier {
    public static final String GCP_ISSUER = "https://confidentialcomputing.googleapis.com";
    public static final String GCP_JWKS_URL =
            "https://www.googleapis.com/service_accounts/v1/metadata/jwk/"
                    + "signer@confidentialspace-sign.iam.gserviceaccount.com";
    public static final String EXPORTER_LABEL = "EXPORTER-Channel-Binding";
    public static final int EXPORTER_LENGTH = 32;
    private static final String PRODUCTION_DEBUG_STATUS = "disabled-since-boot";

    private enum BindingMode {
        LIVE_CHANNEL,
        RECEIPT_KEY
    }

    private AttestationVerifier() {}

    public static GatewayAttestation verify(
            byte[] document, AttestationVerificationOptions options)
            throws AttestationVerificationException {
        if (document == null) { throw new NullPointerException("document"); }
        JsonObject claims = verifiedClaims(document, options);
        return verifyClaims(claims, options, BindingMode.LIVE_CHANNEL, null);
    }

    /**
     * Verifies a boot-minted GCP key attestation and requires set membership of a receipt-key
     * commitment in its nonce values.
     *
     * <p>Unlike {@link #verify(byte[], AttestationVerificationOptions)}, this profile deliberately
     * does not require a live TLS certificate, caller nonce, or exporter: the evidence binds a
     * durable receipt key rather than a connection. The Google signature, issuer, expiry,
     * production posture, Confidential Space platform, audience, and supplied image pins are
     * still checked.
     */
    public static void verifyReceiptKeyCommitment(
            byte[] document,
            byte[] commitment,
            AttestationVerificationOptions options)
            throws AttestationVerificationException {
        if (document == null) { throw new NullPointerException("document"); }
        if (commitment == null) { throw new NullPointerException("commitment"); }
        if (options == null) { throw new NullPointerException("options"); }
        if (commitment.length != 32) {
            throw failure("receipt key commitment must be 32 bytes", null);
        }
        JsonObject claims = verifiedClaims(document, options);
        verifyClaims(claims, options, BindingMode.RECEIPT_KEY, commitment);
    }

    private static JsonObject verifiedClaims(
            byte[] document, AttestationVerificationOptions options)
            throws AttestationVerificationException {
        String token = new String(document, StandardCharsets.US_ASCII).trim();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw failure("expected 3 JWT segments, got " + parts.length, null);
        }
        JsonObject header = decodeObject(parts[0], "header");
        JsonObject claims = decodeObject(parts[1], "claims");
        byte[] signature = decodeBase64(parts[2]);
        JsonObject jwks = options.jwks();
        if (jwks == null) { jwks = fetchJwks(options); }
        verifySignature(header, jwks,
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature);
        return claims;
    }

    /**
     * Flagged transport BYPASS, by design: a documented single-shot,
     * credential-free metadata fetch against Google's JWKS endpoint that
     * deliberately stays outside the SDK's transport engine (no retries, no
     * candidate list, no failover). Callers who need control inject their own
     * {@code httpClient} or pre-fetched {@code jwks} via the options.
     */
    private static JsonObject fetchJwks(AttestationVerificationOptions options)
            throws AttestationVerificationException {
        String url = options.jwksUrl() == null ? GCP_JWKS_URL : options.jwksUrl();
        OkHttpClient client = options.httpClient() == null
                ? new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
                : options.httpClient();
        try (Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw failure("JWKS fetch returned HTTP " + response.code(), null);
            }
            JsonElement parsed = JsonSupport.parse(body.string());
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("keys")) {
                throw failure("GCP JWKS returned unexpected shape", null);
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            throw failure("JWKS fetch failed", error);
        }
    }

    private static void verifySignature(
            JsonObject header, JsonObject jwks, byte[] signingInput, byte[] signature)
            throws AttestationVerificationException {
        String algorithm = string(header, "alg");
        if (!"RS256".equals(algorithm)) {
            throw failure("unsupported JWT alg " + algorithm + "; expected RS256", null);
        }
        String kid = string(header, "kid");
        JsonObject key = null;
        JsonArray keys = jwks.has("keys") && jwks.get("keys").isJsonArray()
                ? jwks.getAsJsonArray("keys") : new JsonArray();
        for (JsonElement value : keys) {
            if (value.isJsonObject() && safeEquals(kid, string(value.getAsJsonObject(), "kid"))) {
                key = value.getAsJsonObject();
                break;
            }
        }
        if (key == null) { throw failure("no JWK with matching kid in JWKS", null); }
        if (!"RSA".equals(string(key, "kty"))) { throw failure("expected RSA key in JWKS", null); }
        try {
            BigInteger modulus = new BigInteger(1, decodeBase64(string(key, "n")));
            BigInteger exponent = new BigInteger(1, decodeBase64(string(key, "e")));
            java.security.PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(signingInput);
            if (!verifier.verify(signature)) {
                throw failure("JWT signature verification failed", null);
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            if (error instanceof AttestationVerificationException) {
                throw (AttestationVerificationException) error;
            }
            throw failure("JWT signature verification failed", error);
        }
    }

    private static GatewayAttestation verifyClaims(
            JsonObject claims,
            AttestationVerificationOptions options,
            BindingMode bindingMode,
            byte[] receiptKeyCommitment)
            throws AttestationVerificationException {
        long now = System.currentTimeMillis() / 1000L;
        Long expires = longValue(claims.get("exp"));
        if (expires == null) {
            throw failure("JWT is missing a valid expiration", null);
        }
        if (expires.longValue() <= now) {
            throw failure("JWT expired at " + expires + " (now=" + now + ")", null);
        }
        String issuer = string(claims, "iss");
        if (!GCP_ISSUER.equals(issuer)) {
            throw failure("unexpected issuer " + issuer + "; expected " + GCP_ISSUER, null);
        }
        if (!options.policy().isDebugAllowed()) {
            String debugStatus = string(claims, "dbgstat");
            if (!PRODUCTION_DEBUG_STATUS.equalsIgnoreCase(debugStatus)) {
                throw failure(
                        "debug Confidential Space workload must report disabled-since-boot", null);
            }
        }
        if (!"CONFIDENTIAL_SPACE".equals(string(claims, "swname"))) {
            throw failure("attested workload is not running Confidential Space", null);
        }
        if (!booleanValue(claims.get("secboot"))) {
            throw failure("attested workload does not report Secure Boot", null);
        }
        String hardware = string(claims, "hwmodel");
        if (!"GCP_AMD_SEV".equals(hardware)
                && !"GCP_AMD_SEV_ES".equals(hardware)
                && !"GCP_INTEL_TDX".equals(hardware)) {
            throw failure("unsupported confidential hardware model " + hardware, null);
        }
        List<String> audiences = strings(claims.get("aud"));
        String expectedAudience = options.policy().getAudience();
        if (!audiences.contains(expectedAudience)) {
            throw failure("audience " + expectedAudience + " not present in JWT", null);
        }

        JsonObject submods = object(claims, "submods");
        JsonObject container = object(submods, "container");
        String digest = string(container, "image_digest");
        String reference = string(container, "image_reference");
        if (!options.policy().pinsImageIdentity()) {
            // Defence in depth for hand-built policies: requireOneOf returns
            // immediately on an empty accepted list, so reaching it with
            // nothing pinned would accept any attested workload.
            throw failure("attestation policy pins no image identity; refusing to verify "
                    + "against a policy that cannot distinguish the gateway from any "
                    + "other workload", null);
        }
        requireOneOf("image_digest", digest, options.policy().getExpectedImageDigests());
        requireOneOf("image_reference", reference,
                options.policy().getExpectedImageReferences());

        if (bindingMode == BindingMode.RECEIPT_KEY) {
            List<String> committedValues = stringValues(claims.get("eat_nonce"));
            if (!containsIgnoreCase(committedValues, hex(receiptKeyCommitment))) {
                throw failure("receipt key commitment not present in JWT nonce set", null);
            }
            return new GatewayAttestation(
                    "", empty(digest), empty(reference), hex(receiptKeyCommitment), expires,
                    issuer, expectedAudience, claims);
        }

        List<String> nonces = strings(claims.has("eat_nonce")
                ? claims.get("eat_nonce") : claims.get("nonces"));
        String nonce = options.nonceHex();
        if (nonce != null && !nonces.contains(nonce)) {
            throw failure("nonce not present in JWT nonces", null);
        }
        byte[] exporter = options.tlsExporter();
        if (exporter != null) {
            if (exporter.length != EXPORTER_LENGTH) {
                throw failure("TLS exporter binding must be " + EXPORTER_LENGTH + " bytes", null);
            }
            if (nonce == null) { throw failure("fresh nonce required with exporter binding", null); }
            String exporterHex = hex(exporter);
            if (safeEquals(nonce.toLowerCase(Locale.ROOT), exporterHex)) {
                throw failure("fresh nonce must differ from TLS exporter binding", null);
            }
            if (!containsIgnoreCase(nonces, exporterHex)) {
                throw failure("TLS exporter binding not present in JWT nonces", null);
            }
        }

        byte[] certDer = options.tlsCertificateDer();
        String certHash = string(claims, "tls_cert_sha256");
        if (certHash == null) { certHash = string(claims, "workload_tls_cert_sha256"); }
        String actualHash = certDer == null ? null : sha256Hex(certDer);
        if (certHash == null && actualHash != null && containsIgnoreCase(nonces, actualHash)) {
            certHash = actualHash;
        }
        if (certHash == null || certHash.length() != 64) {
            throw failure("JWT does not commit to a TLS cert SHA-256", null);
        }
        certHash = certHash.toLowerCase(Locale.ROOT);
        if (actualHash != null && !safeEquals(actualHash, certHash)) {
            throw failure("TLS certificate does not match JWT commitment", null);
        }
        requireMatch("TLS cert SHA-256", certHash, options.policy().getExpectedCertSha256());
        return new GatewayAttestation(
                certHash, empty(digest), empty(reference), nonce, expires, issuer,
                expectedAudience, claims);
    }

    private static JsonObject decodeObject(String encoded, String name)
            throws AttestationVerificationException {
        try {
            JsonElement parsed = JsonSupport.parse(new String(
                    decodeBase64(encoded), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) { throw new IllegalArgumentException(name + " is not an object"); }
            return parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw failure("invalid JWT " + name + " encoding", error);
        }
    }

    private static byte[] decodeBase64(String value) {
        if (value == null) { throw new IllegalArgumentException("missing base64url value"); }
        return Base64.getUrlDecoder().decode(value);
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) { return null; }
        return object.get(key).getAsString();
    }

    private static Long longValue(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) { return null; }
        try { return Long.valueOf(value.getAsLong()); } catch (RuntimeException ignored) { return null; }
    }

    private static boolean booleanValue(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static List<String> strings(JsonElement value) {
        List<String> out = new ArrayList<String>();
        if (value == null || value.isJsonNull()) { return out; }
        if (value.isJsonPrimitive()) { out.add(value.getAsString()); return out; }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonPrimitive()) { out.add(item.getAsString()); }
            }
        }
        return out;
    }

    private static List<String> stringValues(JsonElement value) {
        List<String> out = new ArrayList<String>();
        if (value == null || value.isJsonNull()) { return out; }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            out.add(value.getAsString());
            return out;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    private static void requireMatch(String field, String actual, String expected)
            throws AttestationVerificationException {
        if (expected != null && !expected.isEmpty() && !safeEquals(empty(actual), expected)) {
            throw failure(field + " mismatch", null);
        }
    }

    private static void requireOneOf(String field, String actual, List<String> expected)
            throws AttestationVerificationException {
        if (expected == null || expected.isEmpty()) { return; }
        for (String value : expected) {
            if (value != null && !value.isEmpty() && safeEquals(empty(actual), value)) { return; }
        }
        throw failure(field + " mismatch", null);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        for (String value : values) {
            if (safeEquals(value.toLowerCase(Locale.ROOT), expected.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean safeEquals(String left, String right) {
        if (left == null || right == null) { return left == right; }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) throws AttestationVerificationException {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw failure("SHA-256 unavailable", error); }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) { result.append(String.format(Locale.ROOT, "%02x", item & 0xff)); }
        return result.toString();
    }

    private static String empty(String value) { return value == null ? "" : value; }
    private static AttestationVerificationException failure(String message, Throwable cause) {
        return cause == null
                ? new AttestationVerificationException(message)
                : new AttestationVerificationException(message, cause);
    }
}
