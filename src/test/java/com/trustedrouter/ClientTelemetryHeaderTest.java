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
                // Mixed case: the reserved-header strip must be
                // case-insensitive on every path.
                .header("X-TR-Client", "v=1;a=7;s=1")
                .telemetry(Boolean.TRUE)
                .maxRetries(0)
                .build());

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(server.takeRequest().getHeader("x-tr-client"))
                .as("a self-hosted gateway is not TrustedRouter's to measure,"
                        + " and a caller-forged header must not survive either")
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
                // Opt-out must also strip a stale caller-supplied value.
                .header("x-tr-client", "v=1;a=7;s=1")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .telemetry(Boolean.FALSE)
                .maxRetries(0)
                .build());

        client.chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders()).containsExactly((String) null);
        assertThat(recorder.userAgents().get(0)).startsWith("trusted-router-java/");
    }

    @Test void aForgedCallerHeaderIsReplacedByTheEngineValueWhenActive() throws Exception {
        // x-tr-client is SDK-reserved: builder-level AND per-call forgeries
        // (any case) are stripped, and only the recorder's value rides.
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 0).chatCompletions(ChatRequest.builder()
                .message("user", "hi")
                .callOptions(CallOptions.builder()
                        .header("X-Tr-Client", "v=1;a=9;po=timeout;s=1")
                        .build())
                .build());

        assertThat(recorder.clientHeaders()).containsExactly("v=1;a=0;s=0");
    }

    @Test void aForcedRetryAfterASuccessCarriesPoNoneNotOk() throws Exception {
        // 200 + x-should-retry: true forces a retry; "ok" is outside §3.2's
        // po vocabulary, so the retry header must carry po=none;pc=none
        // rather than a header the enclave would drop whole.
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("x-should-retry", "true")
                .setHeader("Retry-After", "0")
                .setBody(completion("again")));
        server.enqueue(json(200, completion("ok")));

        client(recorder, 2).chatCompletions(ChatRequest.builder().message("user", "hi").build());

        assertThat(recorder.clientHeaders().get(0)).isEqualTo("v=1;a=0;s=0");
        assertThat(recorder.clientHeaders().get(1)).matches(
                "^v=1;a=1;po=none;pc=none;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=0;fo=0$");
    }

    @Test void aHostileExceptionSubtypeCannotFailTheRequest() throws Exception {
        // getCause()/getMessage() are overridable; an adversarial IOException
        // from user middleware must not let telemetry replace the engine's
        // retry decision (§2.2). The unrecordable attempt degrades the next
        // header to a fresh a=0 rather than ever throwing.
        IOException hostile = new IOException("outer") {
            @Override public synchronized Throwable getCause() {
                throw new IllegalStateException("hostile cause");
            }

            @Override public String getMessage() {
                throw new IllegalStateException("hostile message");
            }
        };
        WireRecorder recorder = new WireRecorder(server.url("/"));
        recorder.failHost("api.trustedrouter.com", hostile);
        server.enqueue(json(200, completion("ok")));

        assertThat(client(recorder, 2)
                .chatCompletions(ChatRequest.builder().message("user", "hi").build())
                .firstText()).isEqualTo("ok");

        assertThat(recorder.hosts()).containsExactly(
                "api.trustedrouter.com", "api.allyrouter.com");
        assertThat(recorder.clientHeaders()).containsExactly("v=1;a=0;s=0", "v=1;a=0;s=0");
    }

    @Test void aStalledUploadClassifiesAsTimeout() throws Exception {
        // A stalled request-body write: the server accepts the connection
        // and never reads, so the send buffer fills and OkHttp surfaces the
        // generic SocketTimeoutException("timeout"). DOCUMENTED
        // APPROXIMATION (see Telemetry.classifyTransportError): OkHttp gives
        // write stalls the same exception shape as read stalls, so the class
        // is read_timeout; the outcome is correctly timeout either way.
        WireRecorder recorder = new WireRecorder(server.url("/"));
        server.enqueue(new MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.STALL_SOCKET_AT_START));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"c1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .header("x-tr-client", "v=1;a=7;s=1")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .telemetry(Boolean.TRUE)
                .timeoutMillis(500L)
                .maxRetries(2)
                .build());

        try (EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                ChatRequest.builder().message("user", bigPayload()).build())) {
            assertThat(stream.read().getId()).isEqualTo("c1");
        }

        assertThat(recorder.clientHeaders().get(0)).isEqualTo("v=1;a=0;s=1");
        assertThat(recorder.clientHeaders().get(1)).matches(
                "^v=1;a=1;po=timeout;pc=read_timeout;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=1;fo=1$");
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
        // the injected-environment matrix in TelemetryUnitTest. The stale
        // caller-supplied x-tr-client makes every test in this class also
        // prove the reserved-header strip: exact-vector assertions fail if
        // the forged value ever survives to the wire.
        return new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .header("x-tr-client", "v=1;a=7;s=1")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .telemetry(Boolean.TRUE)
                .maxRetries(retries)
                .build());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String bigPayload() {
        // Large enough to overrun any loopback send buffer, so a server
        // that never reads forces a genuine write-phase stall.
        char[] chars = new char[8_000_000];
        java.util.Arrays.fill(chars, 'a');
        return new String(chars);
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
