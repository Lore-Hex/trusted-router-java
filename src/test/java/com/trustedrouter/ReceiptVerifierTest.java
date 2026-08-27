package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.AttestationVerificationException;
import com.trustedrouter.attestation.AttestationVerificationOptions;
import com.trustedrouter.attestation.AttestationVerifier;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.receipts.MissingAttestationException;
import com.trustedrouter.receipts.MissingBindingException;
import com.trustedrouter.receipts.ReceiptAttestationException;
import com.trustedrouter.receipts.ReceiptCapture;
import com.trustedrouter.receipts.ReceiptClaims;
import com.trustedrouter.receipts.ReceiptHashException;
import com.trustedrouter.receipts.ReceiptHeaderException;
import com.trustedrouter.receipts.ReceiptIssuerException;
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
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ReceiptVerifierTest {
    private static final String EXPECTED_ISSUER = "https://api.trustedrouter.com";
    private static final byte[] REQUEST = "request bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESPONSE = "response bytes".getBytes(StandardCharsets.UTF_8);

    private KeyPair signingKey;
    private KeyPair gcpSigningKey;
    private JsonObject gcpJwks;
    private AttestationVerificationOptions gcpAttestationOptions;

    @BeforeEach void setUp() throws Exception {
        signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        gcpSigningKey = rsaGenerator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) gcpSigningKey.getPublic();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kid", "receipt-attestation-test-key");
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("n", unsignedBase64(publicKey.getModulus()));
        jwk.addProperty("e", unsignedBase64(publicKey.getPublicExponent()));
        JsonArray keys = new JsonArray();
        keys.add(jwk);
        gcpJwks = new JsonObject();
        gcpJwks.add("keys", keys);
        gcpAttestationOptions = AttestationVerificationOptions.builder(
                AttestationPolicy.builder().expectedImageDigest("sha256:trusted").build())
                .jwks(gcpJwks)
                .build();
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

    @Test void bindingsAreRequiredByDefaultAndCanBeExplicitlyDisabled() throws Exception {
        String receipt = compactReceipt(baseClaims());
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt, signatureOnlyOptions(EXPECTED_ISSUER).build()))
                .isInstanceOf(MissingBindingException.class)
                .hasMessageContaining(
                        "missing requestBody and responseBody or responseStream");

        ReceiptClaims claims = ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .requireBindings(false)
                        .build());
        assertThat(claims.getIssuer()).isEqualTo(EXPECTED_ISSUER);
    }

    @Test void partialBindingsFailClosedAndNameTheMissingInput() throws Exception {
        String receipt = compactReceipt(baseClaims());
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .requestBody(REQUEST)
                        .build()))
                .isInstanceOf(MissingBindingException.class)
                .hasMessageContaining("missing responseBody or responseStream");
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .responseBody(RESPONSE)
                        .build()))
                .isInstanceOf(MissingBindingException.class)
                .hasMessageContaining("missing requestBody");
    }

    @Test void issuerPinMatchesExactlyAndMismatchIsTyped() throws Exception {
        String receipt = compactReceipt(baseClaims());
        ReceiptClaims verified = ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .requireBindings(false)
                        .build());
        assertThat(verified.getIssuer()).isEqualTo(EXPECTED_ISSUER);

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions("https://other.example")
                        .requireBindings(false)
                        .build()))
                .isInstanceOf(ReceiptIssuerException.class)
                .hasMessageContaining("iss claim check failed: expected");
    }

    @Test void issuerOriginsNormalizeCaseDefaultPortAndTrailingSlash() throws Exception {
        for (String[] origins : new String[][] {
                {"https://API.TrustedRouter.COM/", "HTTPS://api.trustedrouter.com"},
                {"https://API.TrustedRouter.COM:443/", "https://api.trustedrouter.com"},
                {"https://api.trustedrouter.com", "HTTPS://API.TrustedRouter.COM:443/"},
                {"https://API.TrustedRouter.COM:8443/", "https://api.trustedrouter.com:8443"}
        }) {
            JsonObject claims = baseClaims();
            claims.addProperty("iss", origins[0]);
            ReceiptClaims verified = ReceiptVerifier.verifyReceipt(
                    compactReceipt(claims),
                    signatureOnlyOptions(origins[1])
                            .requireBindings(false)
                            .build());
            assertThat(verified.getIssuer()).isEqualTo(origins[0]);
        }
    }

    @Test void issuerNonDefaultPortMustMatchAfterNormalization() throws Exception {
        JsonObject claims = baseClaims();
        claims.addProperty("iss", EXPECTED_ISSUER + ":8443");
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                compactReceipt(claims),
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .requireBindings(false)
                        .build()))
                .isInstanceOf(ReceiptIssuerException.class)
                .hasMessageContaining("iss claim check failed: expected");
    }

    @Test void receiptAndExpectedIssuerMustBeHttpsOrigins() throws Exception {
        JsonObject claims = baseClaims();
        claims.addProperty("iss", "http://api.trustedrouter.com");
        String receipt = compactReceipt(claims);
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                signatureOnlyOptions(EXPECTED_ISSUER)
                        .requireBindings(false)
                        .build()))
                .isInstanceOf(ReceiptIssuerException.class)
                .hasMessageContaining("must use https");

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                compactReceipt(baseClaims()),
                signatureOnlyOptions("http://api.trustedrouter.com")
                        .requireBindings(false)
                        .build()))
                .isInstanceOf(ReceiptIssuerException.class)
                .hasMessageContaining("must use https");
    }

    @Test void receiptIssuerIsNeverUsedToFetchVerificationMaterial() throws Exception {
        String hostileIssuer = "https://evil.example";
        byte[] document = gcpKeyAttestation(receiptKeyCommitment(), 2);
        JsonObject claims = baseClaims();
        claims.addProperty("iss", hostileIssuer);
        claims.remove("att_sha256");
        JsonObject receiptHeader = protectedHeader();
        receiptHeader.addProperty("att", new String(document, StandardCharsets.US_ASCII));
        receiptHeader.addProperty("att_kind", "gcp-cs-jwt");
        String receipt = flattenedReceipt(receiptHeader, claims);
        List<String> requestedHosts = new ArrayList<String>();
        OkHttpClient guardedHttp = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    String host = chain.request().url().host();
                    requestedHosts.add(host);
                    if ("evil.example".equals(host)) {
                        throw new AssertionError(
                                "receipt issuer was dereferenced for verification material");
                    }
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(JsonSupport.GSON.toJson(gcpJwks), null))
                            .build();
                })
                .build();
        AttestationVerificationOptions guardedAttestation =
                AttestationVerificationOptions.builder(
                                AttestationPolicy.builder()
                                        .expectedImageDigest("sha256:trusted")
                                        .build())
                        .httpClient(guardedHttp)
                        .build();

        ReceiptClaims verified = ReceiptVerifier.verifyReceipt(
                receipt,
                ReceiptVerificationOptions.builder(hostileIssuer)
                        .requestBody(REQUEST)
                        .responseBody(RESPONSE)
                        .now(1000L)
                        .gcpAttestationOptions(guardedAttestation)
                        .build());

        assertThat(verified.getIssuer()).isEqualTo(hostileIssuer);
        assertThat(requestedHosts).containsExactly("www.googleapis.com");
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

    @Test void receiptKeyBindingPassesReceiptPathButFailsLivePathAtAnyNoncePosition()
            throws Exception {
        byte[] commitment = receiptKeyCommitment();
        for (int position : new int[] {0, 2}) {
            byte[] document = gcpKeyAttestation(commitment, position);
            JsonObject claims = baseClaims();
            claims.remove("att_sha256");
            JsonObject receiptHeader = protectedHeader();
            receiptHeader.addProperty("att", new String(document, StandardCharsets.US_ASCII));
            receiptHeader.addProperty("att_kind", "gcp-cs-jwt");
            String receipt = flattenedReceipt(receiptHeader, claims);

            ReceiptClaims verified = ReceiptVerifier.verifyReceipt(
                    receipt,
                    baseOptions()
                            .requireAttestation(true)
                            .attestationDocument(document)
                            .gcpAttestationOptions(gcpAttestationOptions)
                            .build());

            assertThat(verified.getAttestationStatus())
                    .isEqualTo(ReceiptClaims.AttestationStatus.VERIFIED);
            assertThatThrownBy(() -> AttestationVerifier.verify(
                    document, gcpAttestationOptions))
                    .isInstanceOf(AttestationVerificationException.class)
                    .hasMessageContaining("TLS cert");
        }
    }

    @Test void receiptKeyBindingRejectsWrongCommitment() throws Exception {
        byte[] wrongCommitment = sha256(
                "another-receipt-key".getBytes(StandardCharsets.US_ASCII));
        byte[] document = gcpKeyAttestation(wrongCommitment, 2);
        JsonObject claims = baseClaims();
        claims.remove("att_sha256");
        JsonObject receiptHeader = protectedHeader();
        receiptHeader.addProperty("att", new String(document, StandardCharsets.US_ASCII));
        receiptHeader.addProperty("att_kind", "gcp-cs-jwt");

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                flattenedReceipt(receiptHeader, claims),
                baseOptions()
                        .requireAttestation(true)
                        .gcpAttestationOptions(gcpAttestationOptions)
                        .build()))
                .isInstanceOf(ReceiptAttestationException.class)
                .hasMessageContaining("commitment");
    }

    @Test void compactReceiptVerifiesSuppliedPinnedAttestationAndRejectsOneByteMismatch()
            throws Exception {
        byte[] document = gcpKeyAttestation(receiptKeyCommitment(), 2);
        JsonObject claims = baseClaims();
        claims.addProperty("att_sha256", b64(sha256(document)));
        String receipt = compactReceipt(claims);

        ReceiptClaims verified = ReceiptVerifier.verifyReceipt(
                receipt,
                baseOptions()
                        .requireAttestation(true)
                        .attestationDocument(document)
                        .gcpAttestationOptions(gcpAttestationOptions)
                        .build());
        assertThat(verified.getAttestationStatus())
                .isEqualTo(ReceiptClaims.AttestationStatus.VERIFIED);

        byte[] changed = document.clone();
        changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                baseOptions()
                        .requireAttestation(true)
                        .attestationDocument(changed)
                        .gcpAttestationOptions(gcpAttestationOptions)
                        .build()))
                .isInstanceOf(ReceiptAttestationException.class)
                .hasMessageContaining("att_sha256 check failed");
    }

    @Test void flattenedReceiptRejectsMismatchedSuppliedAttestation() throws Exception {
        byte[] document = gcpKeyAttestation(receiptKeyCommitment(), 2);
        JsonObject claims = baseClaims();
        claims.remove("att_sha256");
        JsonObject receiptHeader = protectedHeader();
        receiptHeader.addProperty("att", new String(document, StandardCharsets.US_ASCII));
        receiptHeader.addProperty("att_kind", "gcp-cs-jwt");
        String receipt = flattenedReceipt(receiptHeader, claims);
        byte[] changed = new byte[document.length + 1];
        System.arraycopy(document, 0, changed, 0, document.length);
        changed[changed.length - 1] = 'x';

        assertThatThrownBy(() -> ReceiptVerifier.verifyReceipt(
                receipt,
                baseOptions()
                        .requireAttestation(true)
                        .attestationDocument(changed)
                        .gcpAttestationOptions(gcpAttestationOptions)
                        .build()))
                .isInstanceOf(ReceiptAttestationException.class)
                .hasMessageContaining("does not match")
                .hasMessageContaining("embedded attestation");
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
        ReceiptVerificationOptions.Builder options =
                ReceiptVerificationOptions.builder(EXPECTED_ISSUER)
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
                ReceiptVerificationOptions.builder(EXPECTED_ISSUER)
                        .requestBody(REQUEST)
                        .responseStream(stream)
                        .expectedNonce("fixture_nonce")
                        .now(1000L)
                        .requireAttestation(false)
                        .build());
    }

    private ReceiptVerificationOptions.Builder baseOptions() {
        return ReceiptVerificationOptions.builder(EXPECTED_ISSUER)
                .requestBody(REQUEST)
                .responseBody(RESPONSE)
                .expectedNonce("fixture_nonce")
                .now(1000L)
                .requireAttestation(false);
    }

    private ReceiptVerificationOptions.Builder signatureOnlyOptions(String expectedIssuer) {
        return ReceiptVerificationOptions.builder(expectedIssuer)
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

    private byte[] receiptKeyCommitment() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("inference-receipt-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        return digest.digest(rawPublicKey(signingKey));
    }

    private byte[] gcpKeyAttestation(byte[] commitment, int commitmentPosition)
            throws Exception {
        JsonObject attestationClaims = new JsonObject();
        attestationClaims.addProperty("iss", AttestationVerifier.GCP_ISSUER);
        attestationClaims.addProperty("aud", "quill-cloud");
        attestationClaims.addProperty("exp", System.currentTimeMillis() / 1000L + 300L);
        attestationClaims.addProperty("dbgstat", "disabled-since-boot");
        attestationClaims.addProperty("swname", "CONFIDENTIAL_SPACE");
        attestationClaims.addProperty("secboot", true);
        attestationClaims.addProperty("hwmodel", "GCP_INTEL_TDX");
        JsonArray nonces = new JsonArray();
        if (commitmentPosition == 0) { nonces.add(hex(commitment)); }
        nonces.add(repeat('a', 64));
        nonces.add(repeat('b', 64));
        if (commitmentPosition == 2) { nonces.add(hex(commitment)); }
        attestationClaims.add("eat_nonce", nonces);
        JsonObject container = new JsonObject();
        container.addProperty("image_digest", "sha256:trusted");
        container.addProperty("image_reference", "us-docker.pkg.dev/project/image:release");
        JsonObject submods = new JsonObject();
        submods.add("container", container);
        attestationClaims.add("submods", submods);
        return gcpJwt(attestationClaims);
    }

    private byte[] gcpJwt(JsonObject claims) throws Exception {
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("kid", "receipt-attestation-test-key");
        String signingInput = b64(
                JsonSupport.GSON.toJson(header).getBytes(StandardCharsets.UTF_8))
                + "." + b64(JsonSupport.GSON.toJson(claims).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(gcpSigningKey.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return (signingInput + "." + b64(signer.sign())).getBytes(StandardCharsets.US_ASCII);
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
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) { result.append(value); }
        return result.toString();
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
