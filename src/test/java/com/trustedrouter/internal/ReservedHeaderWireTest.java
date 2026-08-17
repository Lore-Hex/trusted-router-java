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
        // End to end through a real OkHttpClient: the caller's interceptor
        // adds the reserved header, and the SDK's network interceptor -
        // appended last - is still the final word before the socket.
        final List<String> atSocket = new ArrayList<String>();
        Interceptor callerForgery = new Interceptor() {
            @Override public Response intercept(Chain chain) throws IOException {
                return chain.proceed(chain.request().newBuilder()
                        .header(ReservedHeader.NAME, "v=1;a=9;po=timeout;s=1").build());
            }
        };
        Interceptor socketProbe = new Interceptor() {
            @Override public Response intercept(Chain chain) throws IOException {
                atSocket.add(chain.request().header(ReservedHeader.NAME));
                return new Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(ResponseBody.create("{}", null)).build();
            }
        };
        // Mirrors RequestFactory: caller interceptors first, then the SDK's
        // enforcer, then a stand-in for the socket.
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(callerForgery)
                .addInterceptor(new ReservedHeader())
                .addInterceptor(socketProbe)
                .build();
        Response response = client.newCall(
                new Request.Builder().url(APEX).build()).execute();
        response.close();
        assertThat(atSocket).containsExactly((String) null);
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

    /** A Chain that records the reserved header of the request it is given. */
    private static final class RecordingChain implements Interceptor.Chain {
        private final Request request;
        private final List<String> sent = new ArrayList<String>();

        RecordingChain(Request request) {
            this.request = request;
        }

        List<String> sent() {
            return sent;
        }

        @Override public Request request() {
            return request;
        }

        @Override public Response proceed(Request proceeded) {
            sent.add(proceeded.header(ReservedHeader.NAME));
            return new Response.Builder().request(proceeded)
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create("{}", null)).build();
        }

        @Override public Connection connection() {
            return null;
        }

        @Override public Call call() {
            return new OkHttpClient().newCall(request);
        }

        @Override public int connectTimeoutMillis() {
            return 0;
        }

        @Override public Interceptor.Chain withConnectTimeout(int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }

        @Override public int readTimeoutMillis() {
            return 0;
        }

        @Override public Interceptor.Chain withReadTimeout(int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }

        @Override public int writeTimeoutMillis() {
            return 0;
        }

        @Override public Interceptor.Chain withWriteTimeout(int timeout, java.util.concurrent.TimeUnit unit) {
            return this;
        }
    }
}
