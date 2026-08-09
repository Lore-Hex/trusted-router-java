package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway's x-should-retry verdict overrides our status heuristics.
 *
 * <p>A status code cannot say whether a provider already ran: a 502 from "could
 * not reach the provider" and a 502 from "the generation succeeded and then
 * settlement failed" are indistinguishable here, and only the second is
 * dangerous to re-send.
 */
final class ShouldRetryHeaderTest {
    private static final String PRIMARY_HOST = "api.trustedrouter.com";

    private MockWebServer server;

    @BeforeEach void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach void stop() throws Exception {
        server.shutdown();
    }

    @Test void aLabelledSpent502IsNotRetriedAndDoesNotMoveDomains() {
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        for (int i = 0; i < 4; i++) {
            server.enqueue(json(502, "{\"error\":{\"message\":\"settlement failed\"}}")
                    .setHeader("x-should-retry", "false"));
        }

        assertThatThrownBy(() -> client(recorder, 3).request("GET", "/models", null, CallOptions.NONE))
                .isInstanceOf(com.trustedrouter.errors.InternalException.class);

        assertThat(recorder.hosts()).as("the gateway said a provider already ran").hasSize(1);
        assertThat(new HashSet<String>(recorder.hosts())).containsExactly(PRIMARY_HOST);
    }

    @Test void anUnlabelled502StillFailsOverSoOlderGatewaysAreUnaffected() throws Exception {
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        server.enqueue(json(502, "{\"error\":{\"message\":\"unavailable\"}}"));
        server.enqueue(json(200, "{\"ok\":true}"));

        assertThat(client(recorder, 2).request("GET", "/models", null, CallOptions.NONE)
                .getAsJsonObject().get("ok").getAsBoolean()).isTrue();
        assertThat(recorder.hosts()).as("lost failover").contains("api.allyrouter.com");
    }

    @Test void aLabelledRetryable400IsRetriedEvenThoughTheStatusSaysOtherwise() throws Exception {
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        server.enqueue(json(400, "{\"error\":{\"message\":\"transient\"}}")
                .setHeader("x-should-retry", "true"));
        server.enqueue(json(200, "{\"ok\":true}"));

        assertThat(client(recorder, 2).request("GET", "/models", null, CallOptions.NONE)
                .getAsJsonObject().get("ok").getAsBoolean()).isTrue();
        assertThat(recorder.hosts()).as("server said retry and we did not").hasSize(2);
    }

    /**
     * regionalFailover used to answer two questions at once: turning it off also
     * stopped retrying 502/503/504 entirely. It now governs only WHERE a retry
     * goes.
     */
    @Test void aPinnedClientStillRetriesInPlace() throws Exception {
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        server.enqueue(json(503, "{\"error\":{\"message\":\"draining\"}}"));
        server.enqueue(json(200, "{\"ok\":true}"));

        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .maxRetries(2)
                .regionalFailover(false)
                .build());

        assertThat(client.request("GET", "/models", null, CallOptions.NONE)
                .getAsJsonObject().get("ok").getAsBoolean()).isTrue();
        assertThat(recorder.hosts()).as("a pinned client should still retry a 503").hasSize(2);
        assertThat(new HashSet<String>(recorder.hosts()))
                .as("but must not move host").containsExactly(PRIMARY_HOST);
    }

    @Test void retryAfterMsIsHonoredAndBeatsRetryAfter() throws Exception {
        // 30s would blow the test timeout and 10ms cannot; the gap is the assertion.
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        server.enqueue(json(429, "{\"error\":{\"message\":\"slow down\"}}")
                .setHeader("retry-after-ms", "10")
                .setHeader("Retry-After", "30"));
        server.enqueue(json(200, "{\"ok\":true}"));

        long started = System.nanoTime();
        client(recorder, 1).request("GET", "/models", null, CallOptions.NONE);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(elapsedMillis).as("retry-after-ms did not win over retry-after")
                .isLessThan(5_000L);
    }

    private TrustedRouterClient client(DomainRecorder recorder, int retries) {
        return new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .maxRetries(retries)
                .build());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    /** Records the domain the SDK chose, then serves it from a local server. */
    private static final class DomainRecorder implements Interceptor {
        private final List<String> seen = new ArrayList<String>();
        private final HttpUrl target;

        DomainRecorder(HttpUrl target) {
            this.target = target;
        }

        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            seen.add(request.url().host());
            return chain.proceed(request.newBuilder()
                    .url(request.url().newBuilder()
                            .scheme(target.scheme())
                            .host(target.host())
                            .port(target.port())
                            .build())
                    .build());
        }

        List<String> hosts() {
            return seen;
        }
    }
}
