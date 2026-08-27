package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trustedrouter.receipts.ReceiptClaims;
import com.trustedrouter.receipts.ReceiptVerificationOptions;
import com.trustedrouter.receipts.ReceiptVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live production receipt verification. Runs only when TRUSTEDROUTER_API_KEY
 * is set:
 *
 * <pre>TRUSTEDROUTER_API_KEY=... ./gradlew test --tests com.trustedrouter.ReceiptLiveSmokeTest</pre>
 *
 * Exercises the receipt-key attestation path against a REAL Confidential
 * Space key-binding document — the layer the frozen parity fixtures cannot
 * reach (their attestations are placeholders).
 */
@EnabledIfEnvironmentVariable(named = "TRUSTEDROUTER_API_KEY", matches = ".+")
final class ReceiptLiveSmokeTest {
    private static final String BASE = "https://api-us-central1.quillrouter.com";
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<byte[]> chat(String body, String nonce) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + System.getenv("TRUSTEDROUTER_API_KEY"))
                .header("Content-Type", "application/json")
                .header("x-inference-receipt", nonce)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void streamingReceiptVerifiesWithFullAttestationChain() throws Exception {
        String body = "{\"model\":\"trustedrouter/auto\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Say PONG.\"}],\"max_tokens\":8}";
        HttpResponse<byte[]> response = chat(body, "java_live_check_1");
        byte[] wire = response.body();
        String receipt = null;
        for (String event : new String(wire, StandardCharsets.UTF_8).split("\n\n")) {
            if (!event.startsWith("data: ") || event.equals("data: [DONE]")) {
                continue;
            }
            try {
                JsonObject object = JsonParser.parseString(event.substring(6)).getAsJsonObject();
                if (object.has("inference_receipt")) {
                    receipt = object.get("inference_receipt").toString();
                }
            } catch (RuntimeException ignored) {
                // non-JSON keep-alive lines are not receipt candidates
            }
        }
        assertThat(receipt).as("receipt event in stream").isNotNull();
        ReceiptClaims claims = ReceiptVerifier.verifyReceipt(
                receipt,
                ReceiptVerificationOptions.builder()
                        .requestBody(body.getBytes(StandardCharsets.UTF_8))
                        .responseStream(wire)
                        .expectedNonce("java_live_check_1")
                        .maxAgeSeconds(300L)
                        .build());
        assertThat(claims.getModel().getSelected()).isNotEmpty();
    }

    @Test
    void compactReceiptVerifiesWithFetchedAttestation() throws Exception {
        String body = "{\"model\":\"trustedrouter/auto\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Say PONG.\"}],\"max_tokens\":8}";
        HttpResponse<byte[]> response = chat(body, "java_live_check_2");
        String compact = response.headers().firstValue("x-inference-receipt").orElseThrow();
        String payload = compact.split("\\.")[1];
        JsonObject claimsJson = JsonParser.parseString(
                        new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String wantAtt = claimsJson.get("att_sha256").getAsString();
        byte[] attestation = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            // A fresh client per fetch: connection reuse pins every retry to
            // the same instance and the per-instance document never matches.
            HttpResponse<byte[]> candidate = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(BASE + "/receipt-attestation"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(candidate.body());
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(digest).equals(wantAtt)) {
                attestation = candidate.body();
                break;
            }
        }
        assertThat(attestation).as("matching per-instance attestation within 12 fetches").isNotNull();
        ReceiptClaims claims = ReceiptVerifier.verifyReceipt(
                compact,
                ReceiptVerificationOptions.builder()
                        .requestBody(body.getBytes(StandardCharsets.UTF_8))
                        .responseBody(response.body())
                        .expectedNonce("java_live_check_2")
                        .maxAgeSeconds(300L)
                        .attestationDocument(attestation)
                        .build());
        assertThat(claims.getModel().getSelected()).isNotEmpty();
    }
}
