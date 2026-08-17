package com.trustedrouter.internal;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Enforces the {@code x-tr-client} reserved-header invariant at the WIRE, on
 * every request that actually leaves the socket. Internal class with no
 * compatibility guarantees.
 *
 * <p>Building the header in {@code RequestFactory} is not sufficient on its
 * own, because three things happen to a request AFTER the SDK has built it:
 *
 * <ol>
 *   <li><b>OkHttp's own follow-ups.</b> {@code RetryAndFollowUpInterceptor}
 *       re-sends a request inside a single {@code Call} — a {@code 503} or
 *       {@code 408} carrying {@code Retry-After: 0}, and any redirect. The
 *       engine's attempt loop never sees those, so a naive stamp puts the SAME
 *       {@code a=0} on two different wire requests and the enclave counts one
 *       logical attempt twice.</li>
 *   <li><b>Redirects to somebody else's host.</b> OkHttp strips
 *       {@code Authorization} across a cross-host redirect but forwards every
 *       other header, so per-attempt reliability facts would ride to a host
 *       that is not TrustedRouter's to measure (&sect;3.2).</li>
 *   <li><b>Interceptors on an injected client.</b> A caller-supplied
 *       {@code OkHttpClient} may carry interceptors that add or rewrite
 *       headers after {@code buildRequest} returns, which would let a forged
 *       value reach the wire even for an opted-out client.</li>
 * </ol>
 *
 * <p>This runs as a NETWORK interceptor appended last, so it is the final
 * word before the socket: it drops any inbound {@code x-tr-client} (whatever
 * put it there), then re-stamps the engine's validated value on exactly the
 * FIRST wire request of each engine attempt, and only when the wire host is
 * still a TrustedRouter host.
 *
 * <p>Telemetry never fails a request (&sect;2.2): every branch here is wrapped
 * so a failure degrades to sending no header rather than breaking the call.
 */
public final class ReservedHeader implements Interceptor {
    /** The SDK-reserved header name, matched case-insensitively by OkHttp. */
    public static final String NAME = "x-tr-client";

    /**
     * The engine's intended header value for one attempt, carried as a request
     * tag so it survives OkHttp's follow-up rebuilds, plus a claim counter so
     * only the first wire request of that attempt is stamped.
     *
     * <p>OkHttp re-sends a {@code 503}/{@code 408} by handing back the very
     * same {@code Request} instance and builds a redirect with
     * {@code request.newBuilder()}; both preserve tags, so one instance sees
     * every wire request derived from one engine attempt.
     */
    public static final class Stamp {
        private final String value;
        private int wireRequests;

        public Stamp(String value) {
            this.value = value;
        }

        /**
         * The value for the next wire request: the engine's value for the
         * first one, then null for every OkHttp-internal follow-up, which the
         * engine's attempt accounting does not cover.
         */
        synchronized String claim() {
            wireRequests++;
            return wireRequests == 1 ? value : null;
        }

        /** How many wire requests this attempt actually produced; for tests. */
        public synchronized int wireRequests() {
            return wireRequests;
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Request enforced;
        try {
            enforced = enforce(request);
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
            enforced = request;
        }
        return chain.proceed(enforced);
    }

    private static Request enforce(Request request) {
        Request.Builder builder = request.newBuilder();
        // Unconditional: no value survives from anywhere but the engine.
        builder.removeHeader(NAME);
        Stamp stamp = request.tag(Stamp.class);
        if (stamp == null) {
            return builder.build();
        }
        String value = stamp.claim();
        if (value == null) {
            return builder.build();
        }
        // A redirect can move the wire request off TrustedRouter entirely; a
        // self-hosted gateway is not TrustedRouter's to measure (§3.2).
        if ("custom".equals(Telemetry.hostEnum(request.url().toString()))) {
            return builder.build();
        }
        builder.header(NAME, value);
        return builder.build();
    }
}
