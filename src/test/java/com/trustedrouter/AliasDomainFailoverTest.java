package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustedrouter.internal.Transport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * The domain is a single point of failure sitting above the whole deployment.
 *
 * <p>Three names resolve to the same attested enclaves, on separate DNS
 * providers. These tests prove a client actually reaches the second one when
 * the first stops answering. The retry machinery existed before this and could
 * never engage: the candidate list had a single entry and every advance is
 * guarded by {@code baseIndex + 1 < urls.size()}.
 */
final class AliasDomainFailoverTest {
    private static final String PRIMARY_HOST = "api.trustedrouter.com";

    private MockWebServer server;

    @BeforeEach void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach void stop() throws Exception {
        server.shutdown();
    }

    @Test void theDefaultCandidateListHasMoreThanOneEntry() {
        List<String> urls = Transport.inferenceBaseUrls(TrustedRouter.DEFAULT_API_BASE_URL, true);

        assertThat(urls).as("failover cannot engage with a single candidate").hasSizeGreaterThan(1);
        assertThat(urls.get(0)).as("primary must be tried first")
                .isEqualTo(TrustedRouter.DEFAULT_API_BASE_URL);
        assertThat(urls).containsAll(TrustedRouter.ALIAS_API_BASE_URLS);
    }

    @Test void aTrailingSlashOnTheDefaultStillActivatesTheAliases() {
        // Comparing a stored base URL against the raw constant is exactly how
        // this degrades back to one entry without any test noticing.
        assertThat(Transport.inferenceBaseUrls("https://api.trustedrouter.com/v1/", true))
                .hasSizeGreaterThan(1);
    }

    @Test void aCustomBaseUrlIsNeverRedirectedToAPublicAlias() {
        // A private deployment or a test server must get exactly what was asked
        // for. Silently sending that traffic to a public alias is worse than
        // failing.
        assertThat(Transport.inferenceBaseUrls("https://my.internal/v1", true))
                .containsExactly("https://my.internal/v1");
    }

    @Test void disablingRegionalFailoverPinsTheClientToOneHost() {
        assertThat(Transport.inferenceBaseUrls(TrustedRouter.DEFAULT_API_BASE_URL, false))
                .containsExactly(TrustedRouter.DEFAULT_API_BASE_URL);
    }

    @Test void aDeadPrimaryDomainReachesAnAlias() throws Exception {
        // The real scenario: the primary domain does not resolve at all. The
        // failure happens before a byte is written, so no server saw the
        // request and moving domains cannot double-execute anything.
        DomainRecorder recorder = new DomainRecorder(server.url("/"), PRIMARY_HOST);
        server.enqueue(json(200, "{\"ok\":true}"));

        assertThat(client(recorder, 2).request("GET", "/models", null, CallOptions.NONE)
                .getAsJsonObject().get("ok").getAsBoolean()).isTrue();

        assertThat(recorder.hosts().get(0)).as("primary must be attempted first")
                .isEqualTo(PRIMARY_HOST);
        assertThat(recorder.hosts()).as("never reached an alias")
                .contains("api.allyrouter.com");
    }

    @Test void a503FromThePrimaryReachesAnAlias() throws Exception {
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        server.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        server.enqueue(json(200, "{\"ok\":true}"));

        assertThat(client(recorder, 2).request("GET", "/models", null, CallOptions.NONE)
                .getAsJsonObject().get("ok").getAsBoolean()).isTrue();

        assertThat(recorder.hosts().get(0)).isEqualTo(PRIMARY_HOST);
        assertThat(recorder.hosts()).as("never reached an alias")
                .contains("api.allyrouter.com");
        // Client telemetry contract v1 (§3.2/§6.4): the alias attempt tells
        // the gateway what it is recovering from — a failed HTTP attempt on
        // the apex, with the candidate index advanced.
        assertThat(recorder.clientHeaders().get(0)).isEqualTo("v=1;a=0;s=0");
        assertThat(recorder.clientHeaders().get(1)).matches(
                "^v=1;a=1;po=http_error;pc=none;ph=apex;pm=\\d{1,7};sm=\\d{1,7};s=0;fo=1$");
    }

    @Test void a500DoesNotMoveToAnotherDomain() {
        // A 500 means a server received and processed the request. Inference is
        // not idempotent, so re-running it on another domain risks charging
        // twice. Failover is for connection failures and 502/503/504 only.
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        for (int i = 0; i < 3; i++) {
            server.enqueue(json(500, "{\"error\":{\"message\":\"boom\"}}"));
        }

        assertThatThrownBy(() -> client(recorder, 2)
                .request("GET", "/models", null, CallOptions.NONE))
                .isInstanceOf(com.trustedrouter.errors.InternalException.class);

        assertThat(new HashSet<String>(recorder.hosts()))
                .as("a 500 leaked to another domain").containsExactly(PRIMARY_HOST);
    }

    @Test void aCustomBaseUrlStaysOnItsOwnHostEvenWhenItFails() {
        // The caller pinned a host; a 503 from it must not become traffic to a
        // public domain they never named.
        DomainRecorder recorder = new DomainRecorder(server.url("/"));
        for (int i = 0; i < 3; i++) {
            server.enqueue(json(503, "{\"error\":{\"message\":\"unavailable\"}}"));
        }
        TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test")
                .baseUrl("https://my.internal/v1")
                .httpClient(new OkHttpClient.Builder().addInterceptor(recorder).build())
                .maxRetries(2)
                .build());

        assertThatThrownBy(() -> client.request("GET", "/models", null, CallOptions.NONE))
                .isInstanceOf(com.trustedrouter.errors.InternalException.class);

        assertThat(new HashSet<String>(recorder.hosts())).containsExactly("my.internal");
    }

    private TrustedRouterClient client(DomainRecorder recorder, int retries) {
        // No baseUrl: the default host is what activates the alias list, and it
        // is the configuration every real caller uses.
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

    /**
     * Records the domain the SDK chose, then serves it from a local mock server.
     *
     * <p>The rewrite happens in an application interceptor, before OkHttp
     * resolves anything, so the real hostnames are never looked up and this test
     * makes no network call.
     */
    private static final class DomainRecorder implements Interceptor {
        private final List<String> seen = new ArrayList<String>();
        private final List<String> clientHeaders = new ArrayList<String>();
        private final Set<String> unreachable;
        private final HttpUrl target;

        DomainRecorder(HttpUrl target, String... unreachableHosts) {
            this.target = target;
            this.unreachable = new HashSet<String>(Arrays.asList(unreachableHosts));
        }

        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            String host = request.url().host();
            seen.add(host);
            clientHeaders.add(request.header("x-tr-client"));
            if (unreachable.contains(host)) {
                throw new IOException("simulated DNS failure for " + host);
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
            return Collections.unmodifiableList(seen);
        }

        List<String> clientHeaders() {
            return Collections.unmodifiableList(clientHeaders);
        }
    }
}
