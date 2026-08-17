package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustedrouter.models.ChatCompletionChunk;
import com.trustedrouter.requests.ChatRequest;
import com.trustedrouter.streaming.EventStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The x-tr-client header channel (client telemetry contract v1, §3.2/§6.4),
 * observed on the wire by driving the REAL transport engine: requests carry
 * the SDK's default TrustedRouter hostnames and an application interceptor
 * records what would have been sent, then serves it from a local mock server
 * — the same mechanism as {@code AliasDomainFailoverTest}, so the header is
 * exactly what production would receive.
 */
final class ClientTelemetryHeaderTest {
    private MockWebServer server;

    @BeforeEach void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach void stop() throws Exception {
        server.shutdown();
    }

    @Test void attemptZeroNonStreamingHeaderIsExactlyTheGoldenVector() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 0).chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders()).containsExactly("v=1;a=0;s=0");
    }

    @Test void attemptZeroStreamingHeaderIsExactlyTheGoldenVector() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"c1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));

        try (EventStream<ChatCompletionChunk> stream = client(recorder, 0)
                .chatCompletionsChunks(ChatRequest.builder().message("user", "hi").build())) {
            assertThat(stream.read().getId()).isEqualTo("c1");
        }

        assertThat(recorder.clientHeaders()).containsExactly("v=1;a=0;s=1");
    }

    @Test void aConnectTimeoutRetryCarriesThePreviousAttemptFacts() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        recorder.failHost("api.trustedrouter.com", new SocketTimeoutException("connect timed out"));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 2).chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.hosts()).startsWith("api.trustedrouter.com", "api.allyrouter.com");
        assertThat(recorder.clientHeaders().get(0)).isEqualTo("v=1;a=0;s=0");
        assertThat(recorder.clientHeaders().get(1)).matches(
                "^v=1;a=1;po=timeout;pc=connect_timeout;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=0;fo=1$");
    }

    @Test void aDnsFailureRetryClassifiesBeforeTheSdkFlattensTheException() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        recorder.failHost("api.trustedrouter.com",
                new UnknownHostException("api.trustedrouter.com: nodename nor servname provided"));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 2).chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders().get(1)).matches(
                "^v=1;a=1;po=transport_error;pc=dns;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=0;fo=1$");
    }

    @Test void aCustomBaseUrlNeverCarriesTheHeaderEvenWhenTelemetryIsForcedOn() throws Exception {
        server.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .baseUrl(server.url("/v1").toString())
                .telemetry(Boolean.TRUE)
                .maxRetries(0)
                .build());

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(server.takeRequest().getHeader("x-tr-client"))
                .as("a self-hosted gateway is not TrustedRouter's to measure")
                .isNull();
    }

    @Test void controlPlaneCallsCarryNoHeader() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, "{\"data\":[]}"));

        client(recorder, 0).models();

        assertThat(recorder.hosts()).containsExactly("trustedrouter.com");
        assertThat(recorder.clientHeaders()).containsExactly((String) null);
    }

    @Test void absoluteMetadataFetchesCarryNoHeader() throws Exception {
        server.enqueue(json(200, "{\"state\":\"up\"}"));

        client(new WireRecorder(server.url("/")), 0)
                .status(server.url("/status.json").toString());

        assertThat(server.takeRequest().getHeader("x-tr-client")).isNull();
    }

    @Test void anExplicitOptOutSuppressesTheHeaderButNotTheUserAgent() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .telemetry(Boolean.FALSE)
                .maxRetries(0)
                .build());

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders()).containsExactly((String) null);
        assertThat(recorder.userAgents().get(0)).startsWith("trusted-router-java/");
    }

    @Test void userAgentParsesUnderTheContractGrammar() throws Exception {
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 0).chatCompletions(ChatRequest.builder().message("user", "hi").build());

        // §3.1: trusted-router-java/SEMVER( runtime/ver)? — the enclave drops
        // anything else, so this regex is pinned to the accepted grammar.
        assertThat(recorder.userAgents().get(0)).matches(
                "^trusted-router-java/(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)"
                        + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                        + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                        + "( java/[0-9A-Za-z.+-]{1,24})?$");
    }

    @Test void defaultResolutionEnablesTheHeaderForTrustedRouterHosts() throws Exception {
        // The default path — no explicit option — must resolve enabled for
        // the SDK's own hosts. Guarded on ambient env rather than mutating
        // it: opt-out env vars legitimately suppress the header.
        Assumptions.assumeTrue(System.getenv("TRUSTEDROUTER_TELEMETRY") == null
                && System.getenv("DO_NOT_TRACK") == null);
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, completion("ok")));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .maxRetries(0)
                .build());

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders()).containsExactly("v=1;a=0;s=0");
    }

    private TrustedRouterClient client(WireRecorder recorder, int retries) {
        // No baseUrl override: the default TrustedRouter hosts are what the
        // header describes, exactly as for every real caller. Telemetry is
        // forced on so these content assertions cannot be skewed by ambient
        // opt-out env vars; default resolution has its own test above and
        // the injected-environment matrix in TelemetryUnitTest.
        return new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .telemetry(Boolean.TRUE)
                .maxRetries(retries)
                .build());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String completion(String text) {
        return "{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"model\":\"test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    /**
     * Records the hosts, x-tr-client and User-Agent headers the SDK sent,
     * optionally failing chosen hosts with a real transport exception, then
     * serves everything else from the local mock server. Runs as an
     * application interceptor, before OkHttp resolves anything, so the real
     * hostnames are never looked up.
     */
    private static final class WireRecorder implements Interceptor {
        private final HttpUrl target;
        private final Map<String, IOException> failures = new HashMap<String, IOException>();
        private final List<String> hosts = new ArrayList<String>();
        private final List<String> clientHeaders = new ArrayList<String>();
        private final List<String> userAgents = new ArrayList<String>();

        WireRecorder(HttpUrl target) {
            this.target = target;
        }

        void failHost(String host, IOException failure) {
            failures.put(host, failure);
        }

        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            String host = request.url().host();
            hosts.add(host);
            clientHeaders.add(request.header("x-tr-client"));
            userAgents.add(request.header("User-Agent"));
            IOException failure = failures.remove(host);
            if (failure != null) {
                throw failure;
            }
            return chain.proceed(request.newBuilder()
                    .url(request.url().newBuilder()
                            .scheme(target.scheme())
                            .host(target.host())
                            .port(target.port())
                            .build())
                    .build());
        }

        List<String> hosts() {
            return Collections.unmodifiableList(hosts);
        }

        List<String> clientHeaders() {
            return Collections.unmodifiableList(clientHeaders);
        }

        List<String> userAgents() {
            return Collections.unmodifiableList(userAgents);
        }
    }
}
