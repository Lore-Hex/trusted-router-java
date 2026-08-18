package com.trustedrouter.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire-level {@code x-tr-client} invariant (contract v1 §3.2).
 *
 * <p>These assert on the request as it would leave the SOCKET, which is the
 * only layer where OkHttp's own follow-ups, cross-host redirects and caller
 * interceptors are all visible. Focused state tests drive the interceptor
 * directly with real TrustedRouter URLs; TLS fixtures resolve the production
 * hostname to loopback for end-to-end active-stamp and route-recovery tests.
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
        // The complementary active-stamp case uses a local certificate for the
        // production hostname in the next test.
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

    @Test void anActiveEngineStampRidesARealTlsSocket() throws Exception {
        // Resolve the real production hostname to a local TLS server whose
        // certificate names that host. This closes the otherwise-vacuous gap
        // where localhost is correctly classified custom and active stamps
        // are suppressed before reaching MockWebServer.
        HeldCertificate certificate = new HeldCertificate.Builder()
                .commonName("api.trustedrouter.com")
                .addSubjectAlternativeName("api.trustedrouter.com")
                .build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();
        MockWebServer server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        server.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            Dns local = new Dns() {
                @Override public List<InetAddress> lookup(String hostname)
                        throws UnknownHostException {
                    return Collections.singletonList(InetAddress.getByName("127.0.0.1"));
                }
            };
            OkHttpClient injected = new OkHttpClient.Builder()
                    .dns(local)
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .sslSocketFactory(
                            clientCertificates.sslSocketFactory(),
                            clientCertificates.trustManager())
                    .build();
            RequestFactory factory = new RequestFactory(
                    com.trustedrouter.TrustedRouterOptions.builder()
                            .apiKey("sk-test")
                            .httpClient(injected)
                            .build());
            HttpUrl url = server.url("/v1/chat/completions").newBuilder()
                    .host("api.trustedrouter.com")
                    .build();
            Request request = factory.buildRequest(
                    url.toString(), "GET", null,
                    com.trustedrouter.CallOptions.NONE, true, VALUE);
            Response response = factory.requestClient(
                    com.trustedrouter.CallOptions.NONE, false)
                    .newCall(request).execute();
            response.close();

            assertThat(server.takeRequest().getHeader(ReservedHeader.NAME)).isEqualTo(VALUE);
        } finally {
            server.shutdown();
        }
    }

    @Test void aRealPostSendDisconnectIsNeverRecoveredInsideOkHttp() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .commonName("api.trustedrouter.com")
                .addSubjectAlternativeName("api.trustedrouter.com")
                .build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate())
                .build();
        MockWebServer server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
        server.start();
        try {
            // The peer reads the complete request and drops the socket before
            // response headers. OkHttp recovery is disabled: the engine must
            // decide whether replay is safe, consume retry budget, and assign
            // the next attempt index rather than allowing a hidden send.
            server.enqueue(new MockResponse()
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            Dns local = new Dns() {
                @Override public List<InetAddress> lookup(String hostname)
                        throws UnknownHostException {
                    List<InetAddress> routes = new ArrayList<InetAddress>();
                    routes.add(InetAddress.getByName("127.0.0.1"));
                    routes.add(InetAddress.getByName("127.0.0.1"));
                    return routes;
                }
            };
            OkHttpClient injected = new OkHttpClient.Builder()
                    .dns(local)
                    .fastFallback(false)
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .sslSocketFactory(
                            clientCertificates.sslSocketFactory(),
                            clientCertificates.trustManager())
                    .build();
            RequestFactory factory = new RequestFactory(
                    com.trustedrouter.TrustedRouterOptions.builder()
                            .apiKey("sk-test")
                            .httpClient(injected)
                            .build());
            HttpUrl url = server.url("/v1/chat/completions").newBuilder()
                    .host("api.trustedrouter.com")
                    .build();
            Request request = factory.buildRequest(
                    url.toString(), "POST", new com.google.gson.JsonObject(),
                    com.trustedrouter.CallOptions.NONE, true, VALUE);
            assertThatThrownBy(() -> factory.requestClient(
                    com.trustedrouter.CallOptions.NONE, false)
                    .newCall(request).execute())
                    .isInstanceOf(IOException.class);

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(server.takeRequest().getHeader(ReservedHeader.NAME)).isEqualTo(VALUE);
        } finally {
            server.shutdown();
        }
    }

    @Test void aFailureBeforeTheWriteStartEventLeavesTheLabelForTheRetry() throws Exception {
        // This chain deliberately never fires requestHeadersStart: that absent
        // phase signal, contrasted with HeaderStartFailingChain below, is the
        // evidence that writing did not begin. OkHttp can recover failures in
        // this phase; a network-interceptor pass counter would spend the label
        // on the phantom pass and leave the real request unlabelled.
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

        RecordingChain recovered = new RecordingChain(stamped);
        new ReservedHeader().intercept(recovered);
        assertThat(recovered.sent()).containsExactly(VALUE);
    }

    @Test void aPostStartFailureSpendsTheLabelBeforeOkHttpRecovery() throws Exception {
        // This is the phase the old offer()/response-only-commit design could
        // not distinguish: request writing has started, then proceed throws
        // before a Response exists. OkHttp can recover that same Call; the
        // recovered exchange must not repeat the attempt index.
        Request stamped = stamped(APEX, VALUE);
        OkHttpClient client = ReservedHeader.install(new OkHttpClient());
        Call call = client.newCall(stamped);
        EventListener listener = client.eventListenerFactory().create(call);
        HeaderStartFailingChain reset = new HeaderStartFailingChain(
                stamped, listener, call, new IOException("peer reset after request headers"));

        try {
            new ReservedHeader().intercept(reset);
            org.junit.jupiter.api.Assertions.fail("expected the post-start reset");
        } catch (IOException expected) {
            assertThat(expected).hasMessageContaining("peer reset");
        }
        assertThat(reset.sent()).containsExactly(VALUE);

        RecordingChain recovered = new RecordingChain(stamped);
        new ReservedHeader().intercept(recovered);
        assertThat(recovered.sent()).containsExactly((String) null);
    }

    @Test void theSdkMarkerRunsBeforeAndPreservesAThrowingCallerListener() throws Exception {
        // Composition order matters. A caller listener is allowed by OkHttp's
        // API; replacing it loses tracing, while running it first would let a
        // broken callback throw before the SDK closes the duplicate window.
        AtomicInteger callerStarts = new AtomicInteger();
        EventListener caller = new EventListener() {
            @Override public void requestHeadersStart(Call call) {
                callerStarts.incrementAndGet();
                throw new IllegalStateException("caller listener failed");
            }
        };
        Request stamped = stamped(APEX, VALUE);
        OkHttpClient client = ReservedHeader.install(
                new OkHttpClient.Builder().eventListener(caller).build());
        Call call = client.newCall(stamped);
        EventListener composed = client.eventListenerFactory().create(call);
        HeaderStartFailingChain chain = new HeaderStartFailingChain(
                stamped, composed, call, null);

        try {
            new ReservedHeader().intercept(chain);
            org.junit.jupiter.api.Assertions.fail("expected the caller callback failure");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("caller listener failed");
        }
        assertThat(callerStarts).hasValue(1);
        assertThat(chain.sent()).containsExactly(VALUE);

        // The SDK marker ran first despite the throw: a recovery cannot emit
        // the same a= value again.
        RecordingChain recovered = new RecordingChain(stamped);
        new ReservedHeader().intercept(recovered);
        assertThat(recovered.sent()).containsExactly((String) null);
    }

    @Test void noPublicSurfaceCanMintAnEngineStamp() throws Exception {
        // The pre-telemetry RequestFactory API stays public and compatible,
        // but only its package-private six-argument engine overload accepts a
        // telemetry value. The enforcer and opaque Stamp are not publicly
        // nameable at all.
        assertThat(java.lang.reflect.Modifier.isPublic(
                ReservedHeader.class.getModifiers())).isFalse();
        Class<?> stampClass = Class.forName(
                "com.trustedrouter.internal.ReservedHeader$Stamp");
        assertThat(java.lang.reflect.Modifier.isPrivate(stampClass.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isPublic(
                RequestFactory.class.getConstructor(
                        com.trustedrouter.TrustedRouterOptions.class).getModifiers())).isTrue();
        Method compatibleBuild = RequestFactory.class.getDeclaredMethod(
                "buildRequest", String.class, String.class,
                com.google.gson.JsonElement.class,
                com.trustedrouter.CallOptions.class, boolean.class);
        assertThat(java.lang.reflect.Modifier.isPublic(compatibleBuild.getModifiers())).isTrue();
        Method engineBuild = RequestFactory.class.getDeclaredMethod(
                "buildRequest", String.class, String.class,
                com.google.gson.JsonElement.class,
                com.trustedrouter.CallOptions.class, boolean.class, String.class);
        assertThat(java.lang.reflect.Modifier.isPublic(engineBuild.getModifiers()))
                .as("caller-supplied telemetryHeader must not mint a stamp")
                .isFalse();
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
            "v=2;a=0;s=0",                    // unsupported version
            "v=1;a=0;s=0;note=secret",        // unknown key
            "v=1;v=1;a=0;s=0",                // duplicate key
            "v=1;a=999;s=0",                  // semantic range
            "v=1;a=0;s=7",                    // closed bit vocabulary
            "anything=lowercase_text",         // token shape is insufficient
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
        // interceptor list and listener factory rather than appending or
        // wrapping them again. Exactly one keeps one authority point and one
        // phase marker per client; none would let a forged value reach the
        // socket.
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

        EventListener.Factory listenerFactory = factory.requestClient(
                com.trustedrouter.CallOptions.NONE, false).eventListenerFactory();
        assertThat(factory.requestClient(
                com.trustedrouter.CallOptions.builder().timeoutMillis(500L).build(), false)
                .eventListenerFactory()).isSameAs(listenerFactory);
        assertThat(factory.requestClient(
                com.trustedrouter.CallOptions.builder().timeoutMillis(500L).build(), true)
                .eventListenerFactory()).isSameAs(listenerFactory);

        OkHttpClient once = ReservedHeader.install(new OkHttpClient());
        assertThat(ReservedHeader.install(once)).isSameAs(once);
        assertThat(countEnforcers(once)).isEqualTo(1);
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
        Request.Builder request = new Request.Builder().url(url)
                .header(ReservedHeader.NAME, value);
        ReservedHeader.stamp(request, value);
        return request.build();
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
     * A Chain that fails without firing the write-start event. The missing
     * event is the explicit pre-write phase input to the stamp state machine.
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

    /**
     * A Chain that reaches OkHttp's request-header write boundary and then
     * fails before a Response exists. A null failure lets the listener itself
     * throw, which pins listener composition order.
     */
    private static final class HeaderStartFailingChain extends BaseChain {
        private final EventListener listener;
        private final Call call;
        private final IOException failure;

        HeaderStartFailingChain(
                Request request, EventListener listener, Call call, IOException failure) {
            super(request);
            this.listener = listener;
            this.call = call;
            this.failure = failure;
        }

        @Override public Response proceed(Request proceeded) throws IOException {
            record(proceeded);
            listener.requestHeadersStart(call);
            if (failure != null) {
                throw failure;
            }
            throw new AssertionError("listener was expected to throw");
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
