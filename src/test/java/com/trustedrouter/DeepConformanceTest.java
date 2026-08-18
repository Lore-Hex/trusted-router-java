package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.TrustedRouterException;
import com.trustedrouter.requests.ChatRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Authenticator;
import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test void cancellingAsyncCallWhileBufferedBodyIsStalledReleasesWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch headersReceived = new CountDownLatch(1);
            server.enqueue(json(200, "{\"data\":[]}")
                    .setBodyDelay(30, TimeUnit.SECONDS));
            OkHttpClient injected = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        Response response = chain.proceed(chain.request());
                        headersReceived.countDown();
                        return response;
                    })
                    .build();
            TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                    .apiKey("sk-test")
                    .baseUrl(server.url("/v1").toString())
                    .controlBaseUrl(server.url("/v1").toString())
                    .httpClient(injected)
                    .asyncExecutor(worker)
                    .maxRetries(0)
                    .build());

            CompletableFuture<?> future = client.async().models();
            assertThat(headersReceived.await(2, TimeUnit.SECONDS)).isTrue();
            // Let the supplier enter ResponseBody.string(), after execute()
            // has returned the headers but before the delayed body arrives.
            Thread.sleep(50L);
            assertThat(future.cancel(true)).isTrue();

            CompletableFuture<Void> workerReleased = CompletableFuture.runAsync(() -> {}, worker);
            workerReleased.get(2, TimeUnit.SECONDS);
            assertThat(server.getRequestCount()).isEqualTo(1);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test void callerOriginAuthenticatorCannotCreateHidden401FollowUp() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=\"ambient\"")
                    .setBody("{\"error\":{\"message\":\"credentials required\"}}"));
            server.enqueue(json(200, "{\"data\":[]}"));
            AtomicInteger authenticatorCalls = new AtomicInteger();
            Authenticator ambient = new Authenticator() {
                @Override public Request authenticate(Route route, Response response) {
                    authenticatorCalls.incrementAndGet();
                    return response.request().newBuilder()
                            .header("Authorization", "Basic ambient").build();
                }
            };
            OkHttpClient injected = new OkHttpClient.Builder().authenticator(ambient).build();

            assertThatThrownBy(() -> client(server, 0, injected).models())
                    .isInstanceOf(TrustedRouterException.class)
                    .satisfies(error -> assertThat(((TrustedRouterException) error).getStatusCode())
                            .isEqualTo(401));
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(authenticatorCalls).hasValue(0);
            assertThat(injected.authenticator()).isSameAs(ambient);
        }
    }

    @Test void callerProxyAuthenticatorCannotCreateHidden407FollowUp() throws Exception {
        try (MockWebServer proxyServer = new MockWebServer()) {
            proxyServer.start();
            proxyServer.enqueue(new MockResponse().setResponseCode(407)
                    .setHeader("Proxy-Authenticate", "Basic realm=\"ambient-proxy\"")
                    .setBody("{\"error\":{\"message\":\"proxy credentials required\"}}"));
            proxyServer.enqueue(json(200, "{\"data\":[]}"));
            AtomicInteger authenticatorCalls = new AtomicInteger();
            Authenticator ambient = new Authenticator() {
                @Override public Request authenticate(Route route, Response response) {
                    authenticatorCalls.incrementAndGet();
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", "Basic ambient").build();
                }
            };
            Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyServer.getHostName(), proxyServer.getPort()));
            OkHttpClient injected = new OkHttpClient.Builder()
                    .proxy(proxy).proxyAuthenticator(ambient).build();
            TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                    .apiKey("sk-test")
                    .baseUrl("http://upstream.invalid/v1")
                    .controlBaseUrl("http://upstream.invalid/v1")
                    .httpClient(injected)
                    .maxRetries(0)
                    .build());

            assertThatThrownBy(client::models)
                    .isInstanceOf(TrustedRouterException.class)
                    .satisfies(error -> assertThat(((TrustedRouterException) error).getStatusCode())
                            .isEqualTo(407));
            assertThat(proxyServer.getRequestCount()).isEqualTo(1);
            assertThat(authenticatorCalls).hasValue(0);
            assertThat(injected.proxyAuthenticator()).isSameAs(ambient);
        }
    }

    @Test void okhttpStatusFollowUpsAreOnePhysicalAttemptWithOriginalResponse(
            @TempDir Path cacheDirectory) throws Exception {
        Cache callerCache = new Cache(cacheDirectory.toFile(), 1_048_576L);
        List<Integer> callerApplicationCodes = new ArrayList<Integer>();
        List<Integer> callerNetworkCodes = new ArrayList<Integer>();
        OkHttpClient injected = new OkHttpClient.Builder()
                .cache(callerCache)
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    callerApplicationCodes.add(response.code());
                    return response;
                })
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    callerNetworkCodes.add(response.code());
                    return response;
                })
                .build();
        int applicationInterceptors = injected.interceptors().size();
        int networkInterceptors = injected.networkInterceptors().size();

        int[] statuses = {408, 421, 503, 503, 503};
        String[] retryAfterValues = {"0", "0", "0", "00", "9999999999999999999999999"};
        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            String retryAfter = retryAfterValues[index];
            try (MockWebServer server = new MockWebServer()) {
                String message = "original-" + status + "-" + index;
                String bodyText = "status-body-" + status + "-" + index;
                server.enqueue(new MockResponse()
                        .setStatus("HTTP/1.1 " + status + " " + message)
                        .setHeader("Retry-After", retryAfter)
                        .setHeader("X-Response-Semantics", "kept")
                        .setBody(bodyText));
                // This sentinel proves that an OkHttp-internal follow-up did
                // not escape the SDK attempt counter.
                server.enqueue(json(200, "{\"unexpected\":true}"));
                JsonObject body = new JsonObject();
                body.addProperty("secret", "send-once");

                try (Response response = client(server, 0, injected).rawRequest(
                        "POST", "/generic-mutation", body, CallOptions.NONE)) {
                    assertThat(response.code()).isEqualTo(status);
                    assertThat(response.message()).isEqualTo(message);
                    assertThat(response.header("Retry-After")).isEqualTo(retryAfter);
                    assertThat(response.header("X-Response-Semantics")).isEqualTo("kept");
                    assertThat(response.body().string()).isEqualTo(bodyText);
                    assertThat(response.networkResponse()).isNotNull();
                    assertThat(response.networkResponse().code()).isEqualTo(status);
                }
                assertThat(server.getRequestCount()).isEqualTo(1);
            }
        }

        // RequestFactory clones the injected client; its transport policy is
        // not written back into caller-owned state.
        assertThat(injected.retryOnConnectionFailure()).isTrue();
        assertThat(injected.cache()).isSameAs(callerCache);
        assertThat(injected.interceptors()).hasSize(applicationInterceptors);
        assertThat(injected.networkInterceptors()).hasSize(networkInterceptors);
        assertThat(callerApplicationCodes).containsExactly(408, 421, 503, 503, 503);
        assertThat(callerNetworkCodes).containsExactly(408, 421, 503, 503, 503);
        callerCache.close();
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
