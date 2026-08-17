package com.trustedrouter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire-level {@code x-tr-client} invariant (contract v1 §3.2).
 *
 * <p>These assert on the request as it would leave the SOCKET, which is the
 * only layer where OkHttp's own follow-ups, cross-host redirects and caller
 * interceptors are all visible. A MockWebServer always answers on localhost —
 * a {@code custom} host, where the header is correctly suppressed — so the
 * interceptor is driven directly with real TrustedRouter URLs instead.
 */
final class ReservedHeaderWireTest {
    private static final String APEX = "https://api.trustedrouter.com/v1/chat/completions";
    private static final String ALLY = "https://api.allyrouter.com/v1/chat/completions";
    private static final String ELSEWHERE = "https://evil.example.com/v1/chat/completions";
    private static final String VALUE = "v=1;a=1;po=timeout;pc=connect_timeout"
            + ";ph=apex;pm=12;sm=34;s=0;fo=1";

    @Test void theEngineValueRidesTheFirstWireRequestToATrustedRouterHost() throws Exception {
        RecordingChain chain = new RecordingChain(stamped(APEX, VALUE));
        new ReservedHeader().intercept(chain);
        assertThat(chain.sent()).containsExactly(VALUE);
    }

    @Test void anOkHttpFollowUpNeverRepeatsTheAttemptHeader() throws Exception {
        // A 503/408 with Retry-After: 0 makes OkHttp re-send inside ONE Call,
        // reusing the same Request instance and therefore the same Stamp. The
        // engine's attempt loop never observed that follow-up, so labelling it
        // would put the same a= index on two wire requests.
        Request stamped = stamped(APEX, VALUE);
        RecordingChain first = new RecordingChain(stamped);
        RecordingChain followUp = new RecordingChain(stamped);
        new ReservedHeader().intercept(first);
        new ReservedHeader().intercept(followUp);
        assertThat(first.sent()).containsExactly(VALUE);
        assertThat(followUp.sent()).containsExactly((String) null);
    }

    @Test void aRedirectRebuildAlsoCountsAsAFollowUpAndCarriesNothing() throws Exception {
        // OkHttp builds a redirect with request.newBuilder(), which preserves
        // tags — so the same Stamp sees the redirected wire request.
        Request stamped = stamped(APEX, VALUE);
        new ReservedHeader().intercept(new RecordingChain(stamped));
        RecordingChain redirected = new RecordingChain(
                stamped.newBuilder().url(ALLY).build());
        new ReservedHeader().intercept(redirected);
        assertThat(redirected.sent()).containsExactly((String) null);
    }

    @Test void aRedirectToSomebodyElsesHostNeverCarriesTheHeader() throws Exception {
        // Even as the FIRST wire request of an attempt, a non-TrustedRouter
        // host is not TrustedRouter's to measure: OkHttp forwards every header
        // but Authorization across a cross-host redirect.
        RecordingChain chain = new RecordingChain(stamped(ELSEWHERE, VALUE));
        new ReservedHeader().intercept(chain);
        assertThat(chain.sent()).containsExactly((String) null);
    }

    @Test void aCallerForgedValueIsStrippedAtTheWireWhenTelemetryIsOff() throws Exception {
        // No Stamp at all: an opted-out client, a control-plane call or an
        // absolute fetch. Whatever a caller interceptor added is dropped.
        Request forged = new Request.Builder().url(APEX)
                .header(ReservedHeader.NAME, "v=1;a=9;po=timeout;s=1").build();
        RecordingChain chain = new RecordingChain(forged);
        new ReservedHeader().intercept(chain);
        assertThat(chain.sent()).containsExactly((String) null);
    }

    @Test void aCallerForgedValueCannotReplaceTheEngineValueWhenActive() throws Exception {
        // A caller interceptor overwrote the engine's header; the tag is the
        // authority, so the engine's value is restored.
        Request tampered = stamped(APEX, VALUE).newBuilder()
                .header(ReservedHeader.NAME, "v=1;a=9;po=timeout;s=1").build();
        RecordingChain chain = new RecordingChain(tampered);
        new ReservedHeader().intercept(chain);
        assertThat(chain.sent()).containsExactly(VALUE);
    }

    @Test void aCallerInterceptorOnAnInjectedClientCannotForgeTheHeader() throws Exception {
        // A REAL socket and a REAL network interceptor, in the same order
        // RequestFactory installs them: the caller's application interceptor
        // forges the header, the SDK's enforcer runs as a network interceptor
        // and so gets the last word, and the assertion is what the server
        // actually received.
        //
        // The complementary case — an ACTIVE engine stamp surviving over a real
        // socket — cannot be built here: MockWebServer answers on localhost,
        // which maps to the custom host where the header is correctly
        // suppressed, and giving it a TrustedRouter hostname would need a
        // TLS-terminating mock holding a certificate for that name. That path
        // is covered by the stub-Chain tests above, which drive this same
        // interceptor with real TrustedRouter URLs.
        MockWebServer server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        Interceptor callerForgery = new Interceptor() {
            @Override public Response intercept(Chain chain) throws IOException {
                return chain.proceed(chain.request().newBuilder()
                        .header(ReservedHeader.NAME, "v=1;a=9;po=timeout;s=1").build());
            }
        };
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(callerForgery)
                .addNetworkInterceptor(new ReservedHeader())
                .build();
        Response response = client.newCall(
                new Request.Builder().url(server.url("/v1/chat/completions")).build())
                .execute();
        response.close();

        assertThat(server.takeRequest().getHeader(ReservedHeader.NAME)).isNull();
        server.shutdown();
    }

    @Test void aFailureBeforeAnythingWasSentLeavesTheLabelForTheRetry() throws Exception {
        // OkHttp re-runs the whole chain for a recoverable failure that
        // happened BEFORE the request was written — a pooled connection found
        // dead on acquisition is the common case. A pass counter would spend
        // the label on that phantom pass and leave the request that actually
        // reaches the server unlabelled, dropping a real attempt.
        Request stamped = stamped(APEX, VALUE);
        ThrowingChain deadConnection = new ThrowingChain(stamped);
        try {
            new ReservedHeader().intercept(deadConnection);
            org.junit.jupiter.api.Assertions.fail("expected the pre-send failure");
        } catch (IOException expected) {
            assertThat(expected).hasMessageContaining("connection shut down");
        }
        // The phantom pass was labelled but never answered, so nothing is spent.
        assertThat(deadConnection.sent()).containsExactly(VALUE);
        assertThat(stamped.tag(ReservedHeader.Stamp.class).isCommitted()).isFalse();

        RecordingChain recovered = new RecordingChain(stamped);
        new ReservedHeader().intercept(recovered);
        assertThat(recovered.sent()).containsExactly(VALUE);
        assertThat(stamped.tag(ReservedHeader.Stamp.class).isCommitted()).isTrue();
    }

    @Test void aStampIsOnlyMintableByTheEngine() throws Exception {
        // The forgery channel must not simply move from the header to the tag.
        // Stamp's constructor is package-private, so caller code cannot mint
        // one; this test can only do so because it sits in the same package.
        java.lang.reflect.Constructor<?>[] constructors =
                ReservedHeader.Stamp.class.getDeclaredConstructors();
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            assertThat(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                    .as("a public Stamp constructor would let an injected"
                            + " client's interceptor forge the header")
                    .isFalse();
        }
    }

    @Test void anOutOfGrammarStampSendsNothing() throws Exception {
        // Independent of whatever produced the value: only in-grammar,
        // content-free values reach the wire (§2.1).
        String[] bad = {
            "v=1;a=0;s=0;note=hello world",   // space is out of grammar
            "v=1;a=0;s=0;q=" + "x1234567890123456789012345",  // token over 24
            "not-a-pair",                     // no key=value
            "=1",                             // empty key
            "",                               // empty
        };
        for (String value : bad) {
            RecordingChain chain = new RecordingChain(stamped(APEX, value));
            new ReservedHeader().intercept(chain);
            assertThat(chain.sent())
                    .as("out of grammar: " + value)
                    .containsExactly((String) null);
        }
        // The real thing still rides.
        RecordingChain good = new RecordingChain(stamped(APEX, VALUE));
        new ReservedHeader().intercept(good);
        assertThat(good.sent()).containsExactly(VALUE);
    }

    @Test void theEnforcerIsInstalledExactlyOncePerClient() {
        // Installed once in the RequestFactory constructor; requestClient()
        // derives per-call clients with newBuilder(), which COPIES the
        // interceptor list rather than appending to it. Exactly one matters in
        // both directions: none and a forged value reaches the socket; twice
        // and the second pass claims the Stamp, gets null and strips the
        // header, silently disabling the whole channel.
        RequestFactory factory = new RequestFactory(
                com.trustedrouter.TrustedRouterOptions.builder()
                        .apiKey("sk-test")
                        .build());
        assertThat(countEnforcers(factory.requestClient(
                com.trustedrouter.CallOptions.NONE, false)))
                .as("no per-call timeout: the client is reused as-is")
                .isEqualTo(1);
        assertThat(countEnforcers(factory.requestClient(
                com.trustedrouter.CallOptions.builder().timeoutMillis(500L).build(), false)))
                .as("buffered call with a timeout: newBuilder() must not re-add it")
                .isEqualTo(1);
        assertThat(countEnforcers(factory.requestClient(
                com.trustedrouter.CallOptions.builder().timeoutMillis(500L).build(), true)))
                .as("stream open with a timeout: newBuilder() must not re-add it")
                .isEqualTo(1);
    }

    private static int countEnforcers(OkHttpClient client) {
        int found = 0;
        for (Interceptor interceptor : client.networkInterceptors()) {
            if (interceptor instanceof ReservedHeader) {
                found++;
            }
        }
        return found;
    }

    private static Request stamped(String url, String value) {
        return new Request.Builder().url(url)
                .header(ReservedHeader.NAME, value)
                .tag(ReservedHeader.Stamp.class, new ReservedHeader.Stamp(value))
                .build();
    }

    /**
     * The Chain plumbing both stubs share: holds the request and records the
     * reserved header of whatever it is handed.
     */
    private abstract static class BaseChain implements Interceptor.Chain {
        private final Request request;
        private final List<String> sent = new ArrayList<String>();

        BaseChain(Request request) {
            this.request = request;
        }

        final void record(Request proceeded) {
            sent.add(proceeded.header(ReservedHeader.NAME));
        }

        final List<String> sent() {
            return sent;
        }

        @Override public final Request request() {
            return request;
        }

        @Override public final Connection connection() {
            return null;
        }

        @Override public final Call call() {
            return new OkHttpClient().newCall(request);
        }

        @Override public final int connectTimeoutMillis() {
            return 0;
        }

        @Override public final Interceptor.Chain withConnectTimeout(
                int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }

        @Override public final int readTimeoutMillis() {
            return 0;
        }

        @Override public final Interceptor.Chain withReadTimeout(
                int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }

        @Override public final int writeTimeoutMillis() {
            return 0;
        }

        @Override public final Interceptor.Chain withWriteTimeout(
                int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }
    }

    /**
     * A Chain that records what it was handed and then fails the way OkHttp
     * fails when a pooled connection turns out to be dead before the request
     * was written.
     */
    private static final class ThrowingChain extends BaseChain {
        ThrowingChain(Request request) {
            super(request);
        }

        @Override public Response proceed(Request proceeded) throws IOException {
            record(proceeded);
            throw new IOException("connection shut down");
        }
    }

    /** A Chain that records the request and answers it, like a live server. */
    private static final class RecordingChain extends BaseChain {
        RecordingChain(Request request) {
            super(request);
        }

        @Override public Response proceed(Request proceeded) {
            record(proceeded);
            return new Response.Builder().request(proceeded)
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{}", null)).build();
        }
    }
}
