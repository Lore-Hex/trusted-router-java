package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.receipts.MissingAttestationException;
import com.trustedrouter.receipts.ReceiptCapture;
import com.trustedrouter.receipts.ReceiptClaims;
import com.trustedrouter.receipts.ReceiptHashException;
import com.trustedrouter.receipts.ReceiptHeaderException;
import com.trustedrouter.receipts.ReceiptNonceException;
import com.trustedrouter.receipts.ReceiptSignatureException;
import com.trustedrouter.receipts.ReceiptStructureException;
import com.trustedrouter.receipts.ReceiptTimeException;
import com.trustedrouter.receipts.ReceiptUpstreamException;
import com.trustedrouter.receipts.ReceiptVerificationOptions;
import com.trustedrouter.receipts.ReceiptVerifier;
import com.trustedrouter.receipts.UnsupportedAttestationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ReceiptVerifierTest {
    private static final byte[] REQUEST = "request bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE = "response bytes".getBytes(StandardCharsets.UTF_8);

    private KeyPair signingKey;

    @BeforeEach void setUp() throws Exception {
        signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test void verifiesFrozenCompactBodyFixtureUnmodified() throws Exception {
        ReceiptClaims claims = verifyFixture("compact-body");
        assertThat(claims.getRoute()).isEqualTo("chat.completions");
        assertThat(claims.getResponse().getOf()).isEqualTo("body");
        assertThat(claims.getAttestationStatus())
                .isEqualTo(ReceiptClaims.AttestationStatus.UNVERIFIED_BY_THIS_SDK);
    }

    @Test void verifiesFrozenChatStreamFixtureUnmodified() throws Exception {
        ReceiptClaims claims = verifyFixture("chat-stream");
        assertThat(claims.getRoute()).isEqualTo("chat.completions");
        assertThat(claims.getResponse().getOf()).isEqualTo("sse-data-v1");
        assertThat(claims.getResponse().getEvents()).isEqualTo(3L);
    }

    @Test void verifiesFrozenResponsesStreamFixtureUnmodified() throws Exception {
        ReceiptClaims claims = verifyFixture("responses-stream");
        assertThat(claims.getRoute()).isEqualTo("responses");
        assertThat(claims.getResponse().getOf()).isEqualTo("sse-events-v1");
        assertThat(claims.getResponse().getEvents()).isEqualTo(3L);
    }

    @Test void receiptCapturePreservesChunkedWireBytesAndVerifies() throws Exception {
        StreamFixture fixture = streamReceipt(1L, "gcp-cs-jwt");
        try (ReceiptCapture capture = new ReceiptCapture(
                new ByteArrayInputStream(fixture.stream))) {
            byte[] buffer = new byte[7];
            while (capture.read(buffer) != -1) {
                // Deliberately split fields and event delimiters across reads.
            }
            assertThat(capture.getCapturedBytes()).isEqualTo(fixture.stream);
            assertThat(capture.getReceipt()).isNotNull();
            ReceiptClaims claims = capture.verify(baseOptions().build());
            assertThat(claims.getResponse().getEvents()).isEqualTo(1L);
        }
    }

    @Test void rejectsFlippedPayloadByteAndEditedClaimWithStaleSignature() throws Exception {
        String receipt = compactReceipt(baseClaims());
        JsonObject changed = payload(receipt);
        changed.addProperty("jti", "bhat-1");
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                replacePayload(receipt, changed), baseOptions().build()))
                .isInstanceOf(ReceiptSignatureException.class);

        JsonObject edited = payload(receipt);
        edited.addProperty("route", "responses");
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                replacePayload(receipt, edited), baseOptions().build()))
                .isInstanceOf(ReceiptSignatureException.class);
    }

    @Test void rejectsWrongKeyAndWrongKidBeforeClaims() throws Exception {
        String receipt = compactReceipt(baseClaims());
        JsonObject wrongKeyHeader = header(receipt);
        byte[] otherRawKey = rawPublicKey(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        wrongKeyHeader.getAsJsonObject("jwk").addProperty("x", b64(otherRawKey));
        wrongKeyHeader.addProperty("kid", b64(sha256(otherRawKey)));
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                replaceHeader(receipt, wrongKeyHeader), baseOptions().build()))
                .isInstanceOf(ReceiptSignatureException.class);

        JsonObject wrongKidHeader = header(receipt);
        wrongKidHeader.addProperty("kid", b64(new byte[32]));
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                replaceHeader(receipt, wrongKidHeader), baseOptions().build()))
                .isInstanceOf(ReceiptHeaderException.class);
    }

    @Test void rejectsStreamByteFlipReceiptNotLastAndEventsOffByOne() throws Exception {
        StreamFixture fixture = streamReceipt(1L, "gcp-cs-jwt");
        byte[] flipped = fixture.stream.clone();
        int hello = indexOf(flipped, "hello".getBytes(StandardCharsets.US_ASCII));
        flipped[hello] = 'j';
        assertThatThrownBy(() -> verifyStream(fixture.receipt, flipped))
                .isInstanceOf(ReceiptHashException.class)
                .hasMessageContaining("hash");

        String original = new String(fixture.stream, StandardCharsets.UTF_8);
        byte[] notLast = original.replace(
                "data: [DONE]",
                "data: {\"late\":true}\n\ndata: [DONE]")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifyStream(fixture.receipt, notLast))
                .isInstanceOf(ReceiptHashException.class)
                .hasMessageContaining("not the last");

        StreamFixture offByOne = streamReceipt(2L, "gcp-cs-jwt");
        assertThatThrownBy(() -> verifyStream(offByOne.receipt, offByOne.stream))
                .isInstanceOf(ReceiptHashException.class)
                .hasMessageContaining("events");
    }

    @Test void rejectsFutureIssueTimeExpiredTeeWindowAndNonceMismatch() throws Exception {
        JsonObject future = baseClaims();
        future.addProperty("iat", 1061L);
        assertThatThrownBy(() -> verifyCompact(compactReceipt(future)))
                .isInstanceOf(ReceiptTimeException.class);

        JsonObject expiredWindow = baseClaims();
        JsonObject upstream = new JsonObject();
        upstream.addProperty("tier", "tee-verified");
        upstream.addProperty("policy", "chutes-tdx-nvidia-e2e-v1");
        upstream.addProperty("verified_at", 900L);
        upstream.addProperty("verification_expires_at", 1000L);
        expiredWindow.add("upstream", upstream);
        assertThatThrownBy(() -> verifyCompact(compactReceipt(expiredWindow)))
                .isInstanceOf(ReceiptUpstreamException.class);

        String receipt = compactReceipt(baseClaims());
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(receipt,
                baseOptions().expectedNonce("different_nonce").build()))
                .isInstanceOf(ReceiptNonceException.class);

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(receipt,
                baseOptions().now(1101L).maxAgeSeconds(100L).build()))
                .isInstanceOf(ReceiptTimeException.class)
                .hasMessageContaining("max-age");

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(receipt,
                baseOptions().maxAgeSeconds(-1L).build()))
                .isInstanceOf(ReceiptTimeException.class);
    }

    @Test void rejectsUnsupportedAndMissingAttestationByDefault() throws Exception {
        JsonObject claims = baseClaims();
        claims.remove("att_sha256");
        JsonObject unsupportedHeader = protectedHeader();
        unsupportedHeader.addProperty("att", "not-real-evidence");
        unsupportedHeader.addProperty("att_kind", "aws-nitro-cose");
        String unsupported = flattenedReceipt(unsupportedHeader, claims);
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                unsupported, baseOptions().requireAttestation(true).build()))
                .isInstanceOf(UnsupportedAttestationException.class);

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                compactReceipt(baseClaims()),
                baseOptions().requireAttestation(true).build()))
                .isInstanceOf(MissingAttestationException.class);

        JsonObject missingClaims = baseClaims();
        missingClaims.remove("att_sha256");
        String missingFlattened = flattenedReceipt(protectedHeader(), missingClaims);
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                missingFlattened, baseOptions().requireAttestation(true).build()))
                .isInstanceOf(MissingAttestationException.class);
    }

    @Test void rejectsDuplicateJsonMembersAtNestedDepth() throws Exception {
        String normal = JsonSupport.GSON.toJson(baseClaims());
        String duplicate = normal.replace(
                "\"requested\":\"trustedrouter/auto\"",
                "\"requested\":\"trustedrouter/auto\","
                        + "\"requested\":\"attacker/override\"");
        String receipt = compactReceipt(duplicate);
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt, baseOptions().build()))
                .isInstanceOf(ReceiptStructureException.class)
                .hasMessageContaining("duplicate JSON member");
    }

    @Test void rejectsMultilineDataAndUnknownSseFields() throws Exception {
        StreamFixture fixture = streamReceipt(1L, "gcp-cs-jwt");
        String stream = new String(fixture.stream, StandardCharsets.UTF_8);
        byte[] multiline = stream.replace(
                "data: {\"chunk\":\"hello\"}",
                "data: {\"chunk\":\"hello\"}\ndata: second")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifyStream(fixture.receipt, multiline))
                .isInstanceOf(ReceiptHashException.class)
                .hasMessageContaining("multiple data fields");

        byte[] unknown = stream.replace(
                "data: {\"chunk\":\"hello\"}",
                "id: unsupported\ndata: {\"chunk\":\"hello\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifyStream(fixture.receipt, unknown))
                .isInstanceOf(ReceiptHashException.class)
                .hasMessageContaining("unsupported field");
    }

    private ReceiptClaims verifyFixture(String name) throws Exception {
        byte[] receipt = resource("receipts/" + name + "/receipt.jws");
        byte[] metadataBytes = resource("receipts/" + name + "/metadata.json");
        JsonObject metadata = JsonSupport.parse(
                new String(metadataBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        ReceiptVerificationOptions.Builder options = ReceiptVerificationOptions.builder()
                .requestBody(resource("receipts/" + name + "/request.body"))
                .expectedNonce(metadata.get("expected_nonce").getAsString())
                .now(metadata.get("now").getAsLong())
                .requireAttestation(metadata.get("require_attestation").getAsBoolean());
        if ("compact-body".equals(name)) {
            options.responseBody(resource("receipts/" + name + "/response.body"));
        } else {
            options.responseStream(resource("receipts/" + name + "/response.sse"));
        }
        return ReceiptVerifier.verifyReceipt(receipt, options.build());
    }

    private void verifyCompact(String receipt) throws Exception {
        ReceiptVerifier.verifyReceipt(receipt, baseOptions().build());
    }

    private void verifyStream(String receipt, byte[] stream) throws Exception {
        ReceiptVerifier.verifyReceipt(receipt,
                ReceiptVerificationOptions.builder()
                        .requestBody(REQUEST)
                        .responseStream(stream)
                        .expectedNonce("fixture_nonce")
                        .now(1000L)
                        .requireAttestation(false)
                        .build());
    }

    private ReceiptVerificationOptions.Builder baseOptions() {
        return ReceiptVerificationOptions.builder()
                .requestBody(REQUEST)
                .responseBody(RESPONSE)
                .expectedNonce("fixture_nonce")
                .now(1000L)
                .requireAttestation(false);
    }

    private JsonObject baseClaims() throws Exception {
        JsonObject claims = new JsonObject();
        claims.addProperty("rv", 1);
        claims.addProperty("iss", "https://api.trustedrouter.com");
        claims.addProperty("iat", 1000L);
        claims.addProperty("jti", "chat-1");
        claims.addProperty("nonce", "fixture_nonce");
        claims.addProperty("route", "chat.completions");
        JsonObject request = new JsonObject();
        request.addProperty("alg", "sha256");
        request.addProperty("hash", b64(sha256(REQUEST)));
        request.addProperty("of", "body");
        claims.add("req", request);
        JsonObject response = new JsonObject();
        response.addProperty("alg", "sha256");
        response.addProperty("hash", b64(sha256(RESPONSE)));
        response.addProperty("of", "body");
        claims.add("resp", response);
        JsonObject model = new JsonObject();
        model.addProperty("requested", "trustedrouter/auto");
        model.addProperty("selected", "openai/gpt-4o-mini");
        model.addProperty("provider", "openai");
        model.addProperty("endpoint", "openai/gpt-4o-mini@openai/prepaid");
        claims.add("model", model);
        JsonObject upstream = new JsonObject();
        upstream.addProperty("tier", "tls-webpki");
        claims.add("upstream", upstream);
        claims.addProperty("att_sha256", b64(new byte[32]));
        return claims;
    }

    private StreamFixture streamReceipt(long claimedEvents, String attestationKind)
            throws Exception {
        byte[] payload = "{\"chunk\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        JsonObject claims = baseClaims();
        claims.remove("att_sha256");
        JsonObject response = claims.getAsJsonObject("resp");
        response.addProperty("of", "sse-data-v1");
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        preimage.write(payload);
        preimage.write('\n');
        response.addProperty("hash", b64(sha256(preimage.toByteArray())));
        response.addProperty("events", claimedEvents);
        JsonObject header = protectedHeader();
        header.addProperty("att", "not-real-evidence");
        header.addProperty("att_kind", attestationKind);
        String receipt = flattenedReceipt(header, claims);
        JsonObject receiptObject = JsonSupport.parse(receipt).getAsJsonObject();
        String stream = "data: " + new String(payload, StandardCharsets.UTF_8) + "\n\n"
                + "data: {\"inference_receipt\":"
                + JsonSupport.GSON.toJson(receiptObject) + "}\n\n"
                + "data: [DONE]\n\n";
        return new StreamFixture(receipt, stream.getBytes(StandardCharsets.UTF_8));
    }

    private String compactReceipt(JsonObject claims) throws Exception {
        return compactReceipt(JsonSupport.GSON.toJson(claims));
    }

    private String compactReceipt(String claimsJson) throws Exception {
        return sign(protectedHeader(), claimsJson, false);
    }

    private String flattenedReceipt(JsonObject header, JsonObject claims) throws Exception {
        return sign(header, JsonSupport.GSON.toJson(claims), true);
    }

    private String sign(JsonObject header, String claimsJson, boolean flattened) throws Exception {
        String protectedValue = b64(
                JsonSupport.GSON.toJson(header).getBytes(StandardCharsets.UTF_8));
        String payload = b64(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(signingKey.getPrivate());
        signer.update((protectedValue + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = b64(signer.sign());
        if (!flattened) { return protectedValue + "." + payload + "." + signature; }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("protected", protectedValue);
        receipt.addProperty("payload", payload);
        receipt.addProperty("signature", signature);
        return JsonSupport.GSON.toJson(receipt);
    }

    private JsonObject protectedHeader() throws Exception {
        byte[] rawKey = rawPublicKey(signingKey);
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "OKP");
        jwk.addProperty("crv", "Ed25519");
        jwk.addProperty("x", b64(rawKey));
        JsonObject header = new JsonObject();
        header.addProperty("alg", "EdDSA");
        header.addProperty("typ", "inference-receipt+jws");
        header.addProperty("kid", b64(sha256(rawKey)));
        header.add("jwk", jwk);
        return header;
    }

    private static JsonObject header(String receipt) {
        String encoded = receipt.split("\\.", -1)[0];
        return JsonSupport.parse(new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static JsonObject payload(String receipt) {
        String encoded = receipt.split("\\.", -1)[1];
        return JsonSupport.parse(new String(
                Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static String replaceHeader(String receipt, JsonObject header) {
        String[] parts = receipt.split("\\.", -1);
        return b64(JsonSupport.GSON.toJson(header).getBytes(StandardCharsets.UTF_8))
                + "." + parts[1] + "." + parts[2];
    }

    private static String replacePayload(String receipt, JsonObject payload) {
        String[] parts = receipt.split("\\.", -1);
        return parts[0] + "."
                + b64(JsonSupport.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
    }

    private static byte[] rawPublicKey(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - raw.length, raw, 0, raw.length);
        return raw;
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static int indexOf(byte[] value, byte[] needle) {
        outer:
        for (int index = 0; index <= value.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) { continue outer; }
            }
            return index;
        }
        throw new AssertionError("needle not found");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = ReceiptVerifierTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) { throw new IOException("missing test resource " + name); }
            return stream.readAllBytes();
        }
    }

    private static final class StreamFixture {
        private final String receipt;
        private final byte[] stream;

        private StreamFixture(String receipt, byte[] stream) {
            this.receipt = receipt;
            this.stream = stream;
        }
    }
}
