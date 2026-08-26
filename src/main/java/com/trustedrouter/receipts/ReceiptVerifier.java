package com.trustedrouter.receipts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.attestation.AttestationPolicy;
import com.trustedrouter.attestation.AttestationVerificationException;
import com.trustedrouter.attestation.AttestationVerifier;
import com.trustedrouter.attestation.AttestationVerificationOptions;
import com.trustedrouter.errors.TrustedRouterException;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.models.TrustRelease;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline verification for signed inference receipts (wire format v1). */
public final class ReceiptVerifier {
    private static final String RECEIPT_TYPE = "inference-receipt+jws";
    private static final byte[] KEY_COMMITMENT_DOMAIN =
            "inference-receipt-key-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX = new byte[] {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{1,88}");
    private static final byte[] LF_EVENT_END = new byte[] {'\n', '\n'};
    private static final byte[] CRLF_EVENT_END = new byte[] {'\r', '\n', '\r', '\n'};
    private static final byte[] DATA_PREFIX = "data:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EVENT_PREFIX = "event:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DONE = "[DONE]".getBytes(StandardCharsets.US_ASCII);

    private ReceiptVerifier() {}

    /** Verify with default fail-closed options. */
    public static ReceiptClaims verifyReceipt(String receipt)
            throws ReceiptVerificationException {
        return verifyReceipt(receipt, null);
    }

    /** Verify a compact JWS string or flattened JWS JSON string. */
    public static ReceiptClaims verifyReceipt(
            String receipt, ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (receipt == null) { throw new NullPointerException("receipt"); }
        ReceiptVerificationOptions checkedOptions = options == null
                ? ReceiptVerificationOptions.builder().build() : options;
        Envelope envelope = parseEnvelope(receipt);
        Header header = parseHeader(envelope.header);
        verifySignature(envelope, header.publicKey);
        return verifyClaimsAndInputs(envelope, header, checkedOptions);
    }

    /** Verify a previously parsed flattened JWS object. */
    public static ReceiptClaims verifyReceipt(
            JsonObject receipt, ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (receipt == null) { throw new NullPointerException("receipt"); }
        return verifyReceipt(JsonSupport.GSON.toJson(receipt), options);
    }

    /** Verify a parsed flattened JWS object with default fail-closed options. */
    public static ReceiptClaims verifyReceipt(JsonObject receipt)
            throws ReceiptVerificationException {
        return verifyReceipt(receipt, null);
    }

    /** Verify a compact or flattened ASCII JWS value. */
    public static ReceiptClaims verifyReceipt(
            byte[] receipt, ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (receipt == null) { throw new NullPointerException("receipt"); }
        try {
            return verifyReceipt(StandardCharsets.US_ASCII.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(receipt)).toString(), options);
        } catch (CharacterCodingException error) {
            throw new ReceiptStructureException(
                    "JWS structure check failed: receipt bytes must be ASCII", error);
        }
    }

    /** Verify an ASCII JWS value with default fail-closed options. */
    public static ReceiptClaims verifyReceipt(byte[] receipt)
            throws ReceiptVerificationException {
        return verifyReceipt(receipt, null);
    }

    private static Envelope parseEnvelope(String receipt) throws ReceiptStructureException {
        String text = receipt.trim();
        String protectedValue;
        String payload;
        String signature;
        JsonObject flattened = null;
        if (text.startsWith("{")) {
            JsonElement parsed = parseJson(text, "JWS structure");
            if (!parsed.isJsonObject()) {
                throw new ReceiptStructureException(
                        "JWS structure check failed: flattened JWS must be a JSON object");
            }
            flattened = parsed.getAsJsonObject();
            if (flattened.has("header")) {
                throw new ReceiptStructureException(
                        "JWS structure check failed: unprotected flattened headers are not allowed");
            }
            protectedValue = requiredEnvelopeString(flattened, "protected");
            payload = requiredEnvelopeString(flattened, "payload");
            signature = requiredEnvelopeString(flattened, "signature");
        } else {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty()
                    || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new ReceiptStructureException(
                        "JWS structure check failed: compact JWS must have 3 non-empty segments");
            }
            protectedValue = parts[0];
            payload = parts[1];
            signature = parts[2];
        }
        byte[] protectedBytes = decodeBase64Url(protectedValue, "protected header", false);
        byte[] payloadBytes = decodeBase64Url(payload, "JWS payload", false);
        JsonElement header = parseJson(protectedBytes, "protected header");
        JsonElement claims = parseJson(payloadBytes, "receipt claims");
        return new Envelope(
                protectedValue, payload, signature, flattened, header, claims);
    }

    private static String requiredEnvelopeString(JsonObject value, String name)
            throws ReceiptStructureException {
        JsonElement member = value.get(name);
        if (!isString(member) || member.getAsString().isEmpty()) {
            throw new ReceiptStructureException(
                    "JWS structure check failed: flattened JWS requires non-empty string "
                            + "protected, payload, and signature members");
        }
        return member.getAsString();
    }

    private static Header parseHeader(JsonElement value)
            throws ReceiptHeaderException, ReceiptStructureException {
        if (!value.isJsonObject()) {
            throw new ReceiptHeaderException(
                    "protected header check failed: header must be a JSON object");
        }
        JsonObject header = value.getAsJsonObject();
        if (!"EdDSA".equals(stringOrNull(header.get("alg")))) {
            throw new ReceiptHeaderException(
                    "protected header alg check failed: expected 'EdDSA'");
        }
        if (!RECEIPT_TYPE.equals(stringOrNull(header.get("typ")))) {
            throw new ReceiptHeaderException(
                    "protected header typ check failed: expected '" + RECEIPT_TYPE + "'");
        }
        JsonElement jwkValue = header.get("jwk");
        if (jwkValue == null || !jwkValue.isJsonObject()) {
            throw new ReceiptHeaderException(
                    "protected header jwk check failed: jwk must be an object");
        }
        JsonObject jwk = jwkValue.getAsJsonObject();
        if (!"OKP".equals(stringOrNull(jwk.get("kty")))
                || !"Ed25519".equals(stringOrNull(jwk.get("crv"))) || jwk.has("d")) {
            throw new ReceiptHeaderException(
                    "protected header jwk check failed: expected a public OKP/Ed25519 JWK");
        }
        String x = stringOrNull(jwk.get("x"));
        if (x == null) {
            throw new ReceiptHeaderException(
                    "protected header jwk.x check failed: x must be a string");
        }
        byte[] publicKey;
        try {
            publicKey = decodeBase64Url(x, "protected header jwk.x", false);
        } catch (ReceiptStructureException error) {
            throw new ReceiptHeaderException(error.getMessage(), error);
        }
        if (publicKey.length != 32) {
            throw new ReceiptHeaderException(
                    "protected header jwk.x check failed: Ed25519 public key is "
                            + publicKey.length + " bytes, expected 32");
        }
        String kid = stringOrNull(header.get("kid"));
        String expectedKid = encodeBase64Url(sha256(publicKey));
        if (kid == null || !constantTimeEquals(kid, expectedKid)) {
            throw new ReceiptHeaderException(
                    "protected header kid check failed: kid does not equal "
                            + "b64url(sha256(jwk.x))");
        }
        return new Header(header, publicKey);
    }

    private static void verifySignature(Envelope envelope, byte[] rawPublicKey)
            throws ReceiptSignatureException {
        byte[] signature;
        try {
            signature = decodeBase64Url(envelope.signature, "JWS signature", false);
        } catch (ReceiptStructureException error) {
            throw new ReceiptSignatureException(error.getMessage(), error);
        }
        byte[] encodedKey = new byte[ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.length
                + rawPublicKey.length];
        System.arraycopy(ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX, 0, encodedKey, 0,
                ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.length);
        System.arraycopy(rawPublicKey, 0, encodedKey,
                ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.length, rawPublicKey.length);
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encodedKey));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((envelope.protectedValue + "." + envelope.payload)
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                throw new ReceiptSignatureException("Ed25519 signature check failed");
            }
        } catch (ReceiptSignatureException error) {
            throw error;
        } catch (GeneralSecurityException error) {
            throw new ReceiptSignatureException("Ed25519 signature check failed", error);
        }
    }

    private static ReceiptClaims verifyClaimsAndInputs(
            Envelope envelope, Header header, ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (!envelope.claims.isJsonObject()) {
            throw new ReceiptClaimsException(
                    "rv claim check failed: receipt claims must be a JSON object");
        }
        JsonObject claims = envelope.claims.getAsJsonObject();
        long rv = integer(claims.get("rv"), "rv claim", ErrorFamily.CLAIMS);
        if (rv != 1L) {
            throw new ReceiptClaimsException(
                    "rv claim check failed: expected integer 1, got " + rv);
        }
        long issuedAt = integer(claims.get("iat"), "iat claim", ErrorFamily.TIME);
        long now = options.now() == null
                ? Instant.now().getEpochSecond() : options.now().longValue();
        BigInteger futureOffset = BigInteger.valueOf(issuedAt)
                .subtract(BigInteger.valueOf(now));
        if (futureOffset.compareTo(BigInteger.valueOf(60L)) > 0) {
            throw new ReceiptTimeException(
                    "iat future-skew check failed: iat is more than 60 seconds after now");
        }
        Long maxAge = options.maxAgeSeconds();
        if (maxAge != null) {
            if (maxAge.longValue() < 0L) {
                throw new ReceiptTimeException(
                        "iat max-age check failed: maxAgeSeconds must be non-negative");
            }
            BigInteger age = BigInteger.valueOf(now).subtract(BigInteger.valueOf(issuedAt));
            if (age.compareTo(BigInteger.valueOf(maxAge.longValue())) > 0) {
                throw new ReceiptTimeException(
                        "iat max-age check failed: receipt exceeds maxAgeSeconds");
            }
        }

        String issuer = requiredString(claims, "iss", "claims");
        String id = requiredString(claims, "jti", "claims");
        String generationId = optionalString(claims, "gen", "claims");
        String route = requiredString(claims, "route", "claims");
        if (!"chat.completions".equals(route) && !"responses".equals(route)) {
            throw new ReceiptClaimsException(
                    "route claim check failed: unsupported route '" + route + "'");
        }
        JsonObject modelValue = requiredObject(claims, "model", ErrorFamily.CLAIMS);
        ReceiptModelClaims model = new ReceiptModelClaims(
                requiredString(modelValue, "requested", "model"),
                requiredString(modelValue, "selected", "model"),
                requiredString(modelValue, "provider", "model"),
                requiredString(modelValue, "endpoint", "model"));

        String nonce = null;
        if (claims.has("nonce")) {
            nonce = stringOrNull(claims.get("nonce"));
            if (nonce == null || !NONCE.matcher(nonce).matches()) {
                throw new ReceiptNonceException(
                        "nonce claim check failed: nonce must contain 1-88 "
                                + "base64url characters");
            }
        }
        if (options.expectedNonce() != null
                && (nonce == null || !constantTimeEquals(nonce, options.expectedNonce()))) {
            throw new ReceiptNonceException(
                    "nonce match check failed: receipt does not echo expectedNonce");
        }

        JsonObject upstreamValue = requiredObject(
                claims, "upstream", ErrorFamily.UPSTREAM);
        String tier = stringOrNull(upstreamValue.get("tier"));
        Long verifiedAt = null;
        Long verificationExpiresAt = null;
        if ("tee-verified".equals(tier)) {
            verifiedAt = Long.valueOf(integer(
                    upstreamValue.get("verified_at"),
                    "upstream.verified_at", ErrorFamily.UPSTREAM));
            verificationExpiresAt = Long.valueOf(integer(
                    upstreamValue.get("verification_expires_at"),
                    "upstream.verification_expires_at", ErrorFamily.UPSTREAM));
            if (verifiedAt.longValue() > issuedAt
                    || issuedAt >= verificationExpiresAt.longValue()) {
                throw new ReceiptUpstreamException(
                        "tee-verified window check failed: expected "
                                + "verified_at <= iat < verification_expires_at");
            }
        } else if (!"tls-webpki".equals(tier)) {
            throw new ReceiptUpstreamException(
                    "upstream.tier check failed: unsupported tier");
        }
        String policy = optionalUpstreamString(upstreamValue, "policy");
        if ("tee-verified".equals(tier) && policy == null) {
            throw new ReceiptUpstreamException(
                    "upstream.policy check failed: tee-verified receipts require a policy");
        }
        String certificateSha256 = optionalUpstreamString(
                upstreamValue, "cert_sha256");
        ReceiptUpstreamClaims upstream = new ReceiptUpstreamClaims(
                tier, policy, verifiedAt, verificationExpiresAt, certificateSha256);

        ReceiptHashClaims request = digestClaim(
                requiredObject(claims, "req", ErrorFamily.HASH), "req", false);
        byte[] requestBody = options.requestBody();
        if (requestBody != null && !MessageDigest.isEqual(
                sha256(requestBody), decodeHash(request.getHash(), "req.hash"))) {
            throw new ReceiptHashException(
                    "request body hash check failed: req.hash does not match");
        }

        ReceiptHashClaims response = digestClaim(
                requiredObject(claims, "resp", ErrorFamily.HASH), "resp", true);
        byte[] responseBody = options.responseBody();
        byte[] responseStream = options.responseStream();
        if (responseBody != null && responseStream != null) {
            throw new ReceiptHashException(
                    "response hash check failed: provide responseBody or responseStream, not both");
        }
        byte[] expectedResponseHash = decodeHash(response.getHash(), "resp.hash");
        if (responseBody != null) {
            if (!"body".equals(response.getOf())) {
                throw new ReceiptHashException(
                        "response body hash check failed: resp.of must be 'body'");
            }
            if (!MessageDigest.isEqual(sha256(responseBody), expectedResponseHash)) {
                throw new ReceiptHashException(
                        "response body hash check failed: resp.hash does not match");
            }
        } else if (responseStream != null) {
            if (!"sse-data-v1".equals(response.getOf())
                    && !"sse-events-v1".equals(response.getOf())) {
                throw new ReceiptHashException(
                        "response stream hash check failed: resp.of must be an SSE domain");
            }
            StreamDigest streamDigest = streamDigest(
                    responseStream, response.getOf(), envelope.flattened);
            if (!MessageDigest.isEqual(streamDigest.digest, expectedResponseHash)) {
                throw new ReceiptHashException(
                        "response stream hash check failed: resp.hash does not match");
            }
            if (response.getEvents() == null
                    || streamDigest.events != response.getEvents().longValue()) {
                throw new ReceiptHashException(
                        "response stream events check failed: counted " + streamDigest.events
                                + ", receipt claims " + response.getEvents());
            }
        }

        String attestationSha256 = optionalString(claims, "att_sha256", "claims");
        if (attestationSha256 != null
                && decodeClaimsDigest(attestationSha256, "att_sha256 claim").length != 32) {
            throw new ReceiptClaimsException(
                    "att_sha256 claim check failed: SHA-256 digest must be 32 bytes");
        }
        if (envelope.flattened == null && attestationSha256 == null) {
            throw new ReceiptClaimsException(
                    "att_sha256 claim check failed: compact receipts must pin an "
                            + "attestation document");
        }

        ReceiptClaims.AttestationStatus attestationStatus = verifyAttestation(
                envelope, header, options);
        return new ReceiptClaims(
                (int) rv, issuer, issuedAt, id, generationId, nonce, route,
                request, response, model, upstream, attestationSha256, attestationStatus);
    }

    private static ReceiptClaims.AttestationStatus verifyAttestation(
            Envelope envelope, Header parsedHeader, ReceiptVerificationOptions options)
            throws ReceiptVerificationException {
        if (!options.requireAttestation()) {
            if (envelope.flattened != null && parsedHeader.value.has("att_kind")) {
                String skippedKind = stringOrNull(parsedHeader.value.get("att_kind"));
                if (!"gcp-cs-jwt".equals(skippedKind)) {
                    throw new UnsupportedAttestationException(
                            "attestation kind check failed: unsupported att_kind '"
                                    + skippedKind + "'");
                }
            }
            return ReceiptClaims.AttestationStatus.UNVERIFIED_BY_THIS_SDK;
        }
        if (envelope.flattened == null) {
            throw new MissingAttestationException(
                    "attestation check failed: compact receipts omit attestation evidence; "
                            + "obtain the pinned document or explicitly pass "
                            + "requireAttestation(false)");
        }
        String kind = stringOrNull(parsedHeader.value.get("att_kind"));
        if (kind == null && !parsedHeader.value.has("att_kind")) {
            throw new MissingAttestationException(
                    "attestation check failed: flattened receipt has no att_kind");
        }
        if (!"gcp-cs-jwt".equals(kind)) {
            throw new UnsupportedAttestationException(
                    "attestation kind check failed: unsupported att_kind '" + kind + "'");
        }
        String attestation = stringOrNull(parsedHeader.value.get("att"));
        if (attestation == null || attestation.isEmpty()) {
            throw new MissingAttestationException(
                    "attestation check failed: flattened receipt has no embedded att");
        }
        MessageDigest digest = sha256Digest();
        digest.update(KEY_COMMITMENT_DOMAIN);
        byte[] commitment = digest.digest(parsedHeader.publicKey);
        try {
            AttestationVerificationOptions attestationOptions =
                    options.gcpAttestationOptions();
            if (attestationOptions == null) {
                attestationOptions = defaultGcpAttestationOptions();
            }
            AttestationVerifier.verifyReceiptKeyCommitment(
                    attestation.getBytes(StandardCharsets.US_ASCII),
                    commitment, attestationOptions);
        } catch (AttestationVerificationException | RuntimeException error) {
            throw new ReceiptAttestationException(
                    "GCP attestation check failed: " + error.getMessage(), error);
        }
        return ReceiptClaims.AttestationStatus.VERIFIED;
    }

    private static AttestationVerificationOptions defaultGcpAttestationOptions()
            throws ReceiptAttestationException {
        try (TrustedRouterClient client = new TrustedRouterClient(
                TrustedRouterOptions.builder().maxRetries(0).build())) {
            TrustRelease release = client.trustRelease();
            return AttestationVerificationOptions.builder(
                    AttestationPolicy.fromTrustRelease(release)).build();
        } catch (TrustedRouterException | IllegalArgumentException error) {
            throw new ReceiptAttestationException(
                    "GCP attestation check failed: could not load published release pins", error);
        }
    }

    private static ReceiptHashClaims digestClaim(
            JsonObject record, String name, boolean response)
            throws ReceiptHashException {
        String algorithm = stringOrNull(record.get("alg"));
        if (!"sha256".equals(algorithm)) {
            throw new ReceiptHashException(
                    name + ".alg check failed: expected 'sha256'");
        }
        String encoded = stringOrNull(record.get("hash"));
        if (encoded == null) {
            throw new ReceiptHashException(
                    name + ".hash check failed: required string is missing");
        }
        if (decodeHash(encoded, name + ".hash").length != 32) {
            throw new ReceiptHashException(
                    name + ".hash check failed: SHA-256 digest must be 32 bytes");
        }
        String of = stringOrNull(record.get("of"));
        boolean validDomain = response
                ? "body".equals(of) || "sse-data-v1".equals(of)
                        || "sse-events-v1".equals(of)
                : "body".equals(of);
        if (!validDomain) {
            throw new ReceiptHashException(
                    name + ".of check failed: unsupported hash domain");
        }
        JsonElement eventsValue = record.get("events");
        Long events = null;
        if (response && !"body".equals(of)) {
            long count;
            try {
                count = integer(eventsValue, name + ".events", ErrorFamily.HASH);
            } catch (ReceiptVerificationException error) {
                if (error instanceof ReceiptHashException) {
                    throw (ReceiptHashException) error;
                }
                throw new ReceiptHashException(error.getMessage(), error);
            }
            if (count < 0L) {
                throw new ReceiptHashException(
                        name + ".events check failed: expected a non-negative integer");
            }
            events = Long.valueOf(count);
        } else if (eventsValue != null && !eventsValue.isJsonNull()) {
            throw new ReceiptHashException(
                    name + ".events check failed: body receipts must omit events");
        }
        return new ReceiptHashClaims(algorithm, encoded, of, events);
    }

    private static byte[] decodeHash(String value, String check)
            throws ReceiptHashException {
        try {
            return decodeBase64Url(value, check, false);
        } catch (ReceiptStructureException error) {
            throw new ReceiptHashException(error.getMessage(), error);
        }
    }

    private static byte[] decodeClaimsDigest(String value, String check)
            throws ReceiptClaimsException {
        try {
            return decodeBase64Url(value, check, false);
        } catch (ReceiptStructureException error) {
            throw new ReceiptClaimsException(error.getMessage(), error);
        }
    }

    private static StreamDigest streamDigest(
            byte[] stream, String domain, JsonObject expectedReceipt)
            throws ReceiptHashException {
        MessageDigest digest = sha256Digest();
        long events = 0L;
        int offset = 0;
        boolean sawDone = false;
        boolean sawReceipt = false;
        while (offset < stream.length) {
            EventSlice slice = nextEvent(stream, offset);
            if (slice == null) {
                throw new ReceiptHashException(
                        "response stream framing check failed: stream has an incomplete SSE tail");
            }
            offset = slice.end;
            SseEvent event = decodeSseEvent(Arrays.copyOfRange(stream, slice.start, slice.end));
            if (sawDone) {
                throw new ReceiptHashException(
                        "response stream receipt position check failed: data event follows [DONE]");
            }
            if (event.done) {
                sawDone = true;
                continue;
            }
            JsonObject embedded = embeddedReceipt(event.payload);
            if (embedded != null) {
                if (sawReceipt) {
                    throw new ReceiptHashException(
                            "response stream receipt position check failed: multiple receipt events");
                }
                if (expectedReceipt == null || !embedded.equals(expectedReceipt)) {
                    throw new ReceiptHashException(
                            "response stream receipt position check failed: embedded receipt "
                                    + "does not match the verified flattened JWS");
                }
                sawReceipt = true;
                continue;
            }
            if (sawReceipt) {
                throw new ReceiptHashException(
                        "response stream receipt position check failed: receipt is not the last "
                                + "data event before [DONE]");
            }
            if ("sse-data-v1".equals(domain)) {
                if (event.name.length != 0) {
                    throw new ReceiptHashException(
                            "response stream hash check failed: sse-data-v1 events must be unnamed");
                }
            } else {
                digest.update(event.name);
                digest.update((byte) '\n');
            }
            digest.update(event.payload);
            digest.update((byte) '\n');
            events++;
        }
        if (!sawReceipt) {
            throw new ReceiptHashException(
                    "response stream receipt position check failed: receipt event is missing");
        }
        if (!sawDone) {
            throw new ReceiptHashException(
                    "response stream receipt position check failed: receipt is not followed by [DONE]");
        }
        return new StreamDigest(digest.digest(), events);
    }

    static JsonObject discoverReceipt(byte[] stream) {
        int offset = 0;
        JsonObject found = null;
        while (offset < stream.length) {
            EventSlice slice = nextEvent(stream, offset);
            if (slice == null) { break; }
            offset = slice.end;
            try {
                SseEvent event = decodeSseEvent(
                        Arrays.copyOfRange(stream, slice.start, slice.end));
                JsonObject receipt = embeddedReceipt(event.payload);
                if (receipt != null) { found = receipt; }
            } catch (ReceiptVerificationException ignored) {
                // Capture remains byte-exact; verifyReceipt reports malformed framing later.
            }
        }
        return found;
    }

    private static JsonObject embeddedReceipt(byte[] payload)
            throws ReceiptHashException {
        JsonElement decoded;
        try {
            decoded = parseJson(payload, "response stream event JSON");
        } catch (ReceiptStructureException error) {
            return null;
        }
        if (!decoded.isJsonObject()
                || !decoded.getAsJsonObject().has("inference_receipt")) {
            return null;
        }
        JsonElement receipt = decoded.getAsJsonObject().get("inference_receipt");
        if (receipt == null || !receipt.isJsonObject()) {
            throw new ReceiptHashException(
                    "response stream receipt position check failed: inference_receipt must be "
                            + "a flattened JWS object");
        }
        return receipt.getAsJsonObject();
    }

    private static SseEvent decodeSseEvent(byte[] raw) throws ReceiptHashException {
        int bodyEnd;
        if (endsWith(raw, CRLF_EVENT_END)) {
            bodyEnd = raw.length - CRLF_EVENT_END.length;
        } else if (endsWith(raw, LF_EVENT_END)) {
            bodyEnd = raw.length - LF_EVENT_END.length;
        } else {
            throw new ReceiptHashException(
                    "response stream framing check failed: incomplete SSE event");
        }
        byte[] name = new byte[0];
        byte[] payload = new byte[0];
        boolean sawName = false;
        boolean sawData = false;
        int lineStart = 0;
        for (int index = 0; index <= bodyEnd; index++) {
            if (index < bodyEnd && raw[index] != '\n') { continue; }
            int lineEnd = index;
            if (lineEnd > lineStart && raw[lineEnd - 1] == '\r') { lineEnd--; }
            byte[] line = Arrays.copyOfRange(raw, lineStart, lineEnd);
            lineStart = index + 1;
            if (startsWith(line, DATA_PREFIX)) {
                if (sawData) {
                    throw new ReceiptHashException(
                            "response stream framing check failed: SSE event has multiple data fields");
                }
                sawData = true;
                int valueStart = DATA_PREFIX.length;
                if (valueStart < line.length && line[valueStart] == ' ') { valueStart++; }
                payload = Arrays.copyOfRange(line, valueStart, line.length);
            } else if (startsWith(line, EVENT_PREFIX)) {
                if (sawName) {
                    throw new ReceiptHashException(
                            "response stream framing check failed: SSE event has multiple event fields");
                }
                sawName = true;
                int valueStart = EVENT_PREFIX.length;
                if (valueStart < line.length && line[valueStart] == ' ') { valueStart++; }
                name = Arrays.copyOfRange(line, valueStart, line.length);
            } else {
                throw new ReceiptHashException(
                        "response stream framing check failed: SSE event contains an unsupported field");
            }
        }
        if (!sawData) {
            throw new ReceiptHashException(
                    "response stream framing check failed: SSE event has no data field");
        }
        return new SseEvent(name, payload, Arrays.equals(payload, DONE));
    }

    private static EventSlice nextEvent(byte[] data, int offset) {
        int lf = indexOf(data, LF_EVENT_END, offset);
        int crlf = indexOf(data, CRLF_EVENT_END, offset);
        if (lf < 0 && crlf < 0) { return null; }
        if (lf >= 0 && (crlf < 0 || lf < crlf)) {
            return new EventSlice(offset, lf + LF_EVENT_END.length);
        }
        return new EventSlice(offset, crlf + CRLF_EVENT_END.length);
    }

    private static int indexOf(byte[] value, byte[] needle, int start) {
        outer:
        for (int index = start; index <= value.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) { continue outer; }
            }
            return index;
        }
        return -1;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) { return false; }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) { return false; }
        }
        return true;
    }

    private static boolean endsWith(byte[] value, byte[] suffix) {
        if (value.length < suffix.length) { return false; }
        int start = value.length - suffix.length;
        for (int index = 0; index < suffix.length; index++) {
            if (value[start + index] != suffix[index]) { return false; }
        }
        return true;
    }

    private static JsonObject requiredObject(
            JsonObject parent, String name, ErrorFamily family)
            throws ReceiptVerificationException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonObject()) {
            throw family.error(
                    name + " claim check failed: required object is missing or invalid");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject parent, String name, String family)
            throws ReceiptClaimsException {
        String value = stringOrNull(parent.get(name));
        if (value == null || value.isEmpty()) {
            throw new ReceiptClaimsException(
                    family + " " + name + " check failed: required string is missing or empty");
        }
        return value;
    }

    private static String optionalString(JsonObject parent, String name, String family)
            throws ReceiptClaimsException {
        if (!parent.has(name)) { return null; }
        String value = stringOrNull(parent.get(name));
        if (value == null || value.isEmpty()) {
            throw new ReceiptClaimsException(
                    family + " " + name + " check failed: value must be a non-empty string");
        }
        return value;
    }

    private static String optionalUpstreamString(JsonObject parent, String name)
            throws ReceiptUpstreamException {
        if (!parent.has(name)) { return null; }
        String value = stringOrNull(parent.get(name));
        if (value == null || value.isEmpty()) {
            throw new ReceiptUpstreamException(
                    "upstream " + name + " check failed: value must be a non-empty string");
        }
        return value;
    }

    private static long integer(JsonElement value, String check, ErrorFamily family)
            throws ReceiptVerificationException {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw family.error(check + " check failed: expected an integer");
        }
        String raw = value.getAsString();
        if (!INTEGER.matcher(raw).matches()) {
            throw family.error(check + " check failed: expected an integer");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw family.error(check + " check failed: integer is outside signed 64-bit range");
        }
    }

    private static String stringOrNull(JsonElement value) {
        return isString(value) ? value.getAsString() : null;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString();
    }

    private static JsonElement parseJson(byte[] value, String check)
            throws ReceiptStructureException {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            return parseJson(decoded, check);
        } catch (CharacterCodingException error) {
            throw new ReceiptStructureException(
                    check + " check failed: JSON must be valid UTF-8", error);
        }
    }

    private static JsonElement parseJson(String value, String check)
            throws ReceiptStructureException {
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = readJsonValue(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("trailing content after JSON value");
            }
            return parsed;
        } catch (IOException | RuntimeException error) {
            throw new ReceiptStructureException(
                    check + " check failed: invalid JSON: " + error.getMessage(), error);
        }
    }

    private static JsonElement readJsonValue(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            JsonObject object = new JsonObject();
            Set<String> names = new HashSet<String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) {
                    throw new IOException("duplicate JSON member '" + name + "'");
                }
                object.add(name, readJsonValue(reader));
            }
            reader.endObject();
            return object;
        }
        if (token == JsonToken.BEGIN_ARRAY) {
            JsonArray array = new JsonArray();
            reader.beginArray();
            while (reader.hasNext()) { array.add(readJsonValue(reader)); }
            reader.endArray();
            return array;
        }
        if (token == JsonToken.STRING) { return new JsonPrimitive(reader.nextString()); }
        if (token == JsonToken.NUMBER) {
            return new JsonPrimitive(new ExactNumber(reader.nextString()));
        }
        if (token == JsonToken.BOOLEAN) { return new JsonPrimitive(reader.nextBoolean()); }
        if (token == JsonToken.NULL) { reader.nextNull(); return JsonNull.INSTANCE; }
        throw new IOException("unexpected JSON token " + token);
    }

    private static byte[] decodeBase64Url(String value, String check, boolean allowEmpty)
            throws ReceiptStructureException {
        if (value == null || (!allowEmpty && value.isEmpty())
                || !BASE64URL.matcher(value).matches() || value.length() % 4 == 1) {
            throw new ReceiptStructureException(
                    check + " check failed: invalid base64url encoding");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new ReceiptStructureException(
                    check + " check failed: invalid base64url encoding", error);
        }
    }

    private static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK has no SHA-256 provider", error);
        }
    }

    private static byte[] sha256(byte[] value) {
        return sha256Digest().digest(value);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private enum ErrorFamily {
        CLAIMS,
        TIME,
        UPSTREAM,
        HASH;

        private ReceiptVerificationException error(String message) {
            if (this == TIME) { return new ReceiptTimeException(message); }
            if (this == UPSTREAM) { return new ReceiptUpstreamException(message); }
            if (this == HASH) { return new ReceiptHashException(message); }
            return new ReceiptClaimsException(message);
        }
    }

    private static final class Envelope {
        private final String protectedValue;
        private final String payload;
        private final String signature;
        private final JsonObject flattened;
        private final JsonElement header;
        private final JsonElement claims;

        private Envelope(
                String protectedValue,
                String payload,
                String signature,
                JsonObject flattened,
                JsonElement header,
                JsonElement claims) {
            this.protectedValue = protectedValue;
            this.payload = payload;
            this.signature = signature;
            this.flattened = flattened;
            this.header = header;
            this.claims = claims;
        }
    }

    private static final class Header {
        private final JsonObject value;
        private final byte[] publicKey;

        private Header(JsonObject value, byte[] publicKey) {
            this.value = value;
            this.publicKey = publicKey;
        }
    }

    private static final class SseEvent {
        private final byte[] name;
        private final byte[] payload;
        private final boolean done;

        private SseEvent(byte[] name, byte[] payload, boolean done) {
            this.name = name;
            this.payload = payload;
            this.done = done;
        }
    }

    private static final class EventSlice {
        private final int start;
        private final int end;

        private EventSlice(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class StreamDigest {
        private final byte[] digest;
        private final long events;

        private StreamDigest(byte[] digest, long events) {
            this.digest = digest;
            this.events = events;
        }
    }

    private static final class ExactNumber extends Number {
        private static final long serialVersionUID = 1L;
        private final String value;

        private ExactNumber(String value) { this.value = value; }
        @Override public int intValue() { return new BigDecimal(value).intValue(); }
        @Override public long longValue() { return new BigDecimal(value).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(value); }
        @Override public double doubleValue() { return Double.parseDouble(value); }
        @Override public String toString() { return value; }
    }
}
