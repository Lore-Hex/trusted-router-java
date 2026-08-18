package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import com.trustedrouter.requests.ChatRequest;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

/** Regression coverage for the cross-SDK deep-conformance transport cases. */
final class DeepConformanceTest {
    @Test void redirectsAreSurfacedAndNeverReplayCredentialsOrBody() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer target = new MockWebServer()) {
            origin.enqueue(new MockResponse().setResponseCode(307)
                    .setHeader("Location", target.url("/collect")));
            target.enqueue(json(200, completion("leaked")));
            TrustedRouterClient client = client(origin, 0, null);

            assertThatThrownBy(() -> client.chatCompletions(
                    ChatRequest.builder().message("user", "secret prompt").build()))
                    .isInstanceOf(TrustedRouterException.class)
                    .satisfies(error -> assertThat(((TrustedRouterException) error).getStatusCode())
                            .isEqualTo(307));

            assertThat(origin.getRequestCount()).isEqualTo(1);
            assertThat(target.getRequestCount()).isZero();
        }
    }

    @Test void highLevelMutationRetriesWithOneStableGeneratedKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(json(200, completion("ok")));
            TrustedRouterClient client = client(server, 1, null);

            assertThat(client.chatCompletions(
                    ChatRequest.builder().message("user", "hi").build()).firstText())
                    .isEqualTo("ok");

            RecordedRequest first = server.takeRequest();
            RecordedRequest second = server.takeRequest();
            assertThat(first.getHeader("Idempotency-Key")).startsWith("tr-req-");
            assertThat(second.getHeader("Idempotency-Key"))
                    .isEqualTo(first.getHeader("Idempotency-Key"));
        }
    }

    @Test void unkeyedGenericMutationIsNeverReplayedAfterDisconnect() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(json(200, "{}"));
            TrustedRouterClient client = client(server, 2, null);
            JsonObject body = new JsonObject();
            body.addProperty("secret", "once");

            assertThatThrownBy(() -> client.request(
                    "POST", "/generic-mutation", body, CallOptions.NONE))
                    .isInstanceOf(InternalException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(server.takeRequest().getHeader("Idempotency-Key")).isNull();
        }
    }

    @Test void credentialFreeOAuthSurvivesInjectedAmbientInterceptors() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(200, "{\"key\":\"delegated\"}"));
            Interceptor ambientCredentials = new Interceptor() {
                @Override public Response intercept(Chain chain) throws IOException {
                    return chain.proceed(chain.request().newBuilder()
                            .header("Authorization", "Bearer ambient")
                            .header("Proxy-Authorization", "Bearer ambient-proxy")
                            .header("Cookie", "session=ambient")
                            .header("X-Api-Key", "ambient-api-key")
                            .header("Idempotency-Key", "ambient-idempotency")
                            .header("x-tr-client", "v=1;a=99;s=0")
                            .header("X-TrustedRouter-Workspace", "ambient-workspace")
                            .build());
                }
            };
            OkHttpClient injected = new OkHttpClient.Builder()
                    .addInterceptor(ambientCredentials).build();
            TrustedRouterClient client = client(server, 0, injected);

            assertThat(client.exchangeOAuthKey("code", "verifier", "S256").getKey())
                    .isEqualTo("delegated");
            RecordedRequest request = server.takeRequest();
            assertThat(request.getHeader("Authorization")).isNull();
            assertThat(request.getHeader("Proxy-Authorization")).isNull();
            assertThat(request.getHeader("Cookie")).isNull();
            assertThat(request.getHeader("X-Api-Key")).isNull();
            assertThat(request.getHeader("Idempotency-Key")).isNull();
            assertThat(request.getHeader("x-tr-client")).isNull();
            assertThat(request.getHeader("X-TrustedRouter-Workspace")).isNull();
        }
    }

    @Test void bufferedTimeoutCoversResponseBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(200, completion("late"))
                    .setBodyDelay(500, TimeUnit.MILLISECONDS));
            TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                    .apiKey("sk-test")
                    .baseUrl(server.url("/v1").toString())
                    .controlBaseUrl(server.url("/v1").toString())
                    .timeoutMillis(50)
                    .maxRetries(0)
                    .build());

            assertThatThrownBy(() -> client.chatCompletions(
                    ChatRequest.builder().message("user", "hi").build()))
                    .isInstanceOf(InternalException.class)
                    .hasMessageContaining("timeout");
        }
    }

    @Test void cancellingAsyncCallCancelsSocketAndPreventsRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            CompletableFuture<?> future = client(server, 2, null).async().models();
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

            assertThat(future.cancel(true)).isTrue();
            assertThat(future.isCancelled()).isTrue();
            Thread.sleep(100L);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    private static TrustedRouterClient client(
            MockWebServer server, int retries, OkHttpClient injected) {
        TrustedRouterOptions.Builder options = TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .workspaceId("ws-test")
                .baseUrl(server.url("/v1").toString())
                .controlBaseUrl(server.url("/v1").toString())
                .maxRetries(retries);
        if (injected != null) {
            options.httpClient(injected);
        }
        return new TrustedRouterClient(options.build());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String completion(String text) {
        return "{\"id\":\"chat_1\",\"object\":\"chat.completion\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}]}";
    }
}
