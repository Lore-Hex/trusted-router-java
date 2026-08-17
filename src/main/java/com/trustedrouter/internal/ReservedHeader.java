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
 * put it there), then re-stamps the engine's validated value on exactly ONE
 * ANSWERED wire request per engine attempt, and only while the wire host is
 * still a TrustedRouter host and the value is still in grammar.
 *
 * <p>The engine's value travels as a request TAG rather than being trusted
 * from the inbound header, because OkHttp preserves tags across its follow-up
 * rebuilds. The tag type is public but only this package can MINT one, so an
 * interceptor on an injected client cannot forge a stamp either.
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
        private boolean committed;

        /**
         * Engine-only: deliberately NOT public. A public constructor would
         * just move the forgery channel from the header to the tag — an
         * interceptor on an injected client could attach its own Stamp and
         * have the enforcer emit it, even for an opted-out client. Only
         * {@code RequestFactory}, in this package, mints one.
         */
        Stamp(String value) {
            this.value = value;
        }

        /**
         * The value for a wire request that is about to be attempted, or null
         * once this attempt has already been labelled on a request that got an
         * answer.
         *
         * <p>Deliberately NOT a pass counter. OkHttp re-runs the whole
         * interceptor chain for a recoverable failure that happened BEFORE the
         * request was written — a pooled connection found dead on acquisition
         * is the common case — and a counter would spend the label on that
         * phantom pass and leave the request that actually reaches the server
         * unlabelled. So the label is only spent when a response comes back
         * ({@link #commit()}); a pass that throws leaves it available for the
         * retry.
         */
        synchronized String offer() {
            return committed ? null : value;
        }

        /**
         * Marks the label spent, called once a response has come back and the
         * request is therefore known to have been sent. Every later wire
         * request in this attempt — OkHttp's 503/408 re-send, a redirect — is
         * a follow-up the engine's accounting does not cover, so it carries
         * nothing rather than repeating this attempt's index.
         */
        synchronized void commit() {
            committed = true;
        }

        /** Whether this attempt's label has been spent; for tests. */
        public synchronized boolean isCommitted() {
            return committed;
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
        Response response = chain.proceed(enforced);
        // A response came back, so this request was really sent: spend the
        // label. Reached only on the success path — if proceed() throws,
        // nothing is committed and OkHttp's own pre-send recovery gets to
        // label the request that actually makes it out.
        try {
            if (enforced.header(NAME) != null) {
                Stamp stamp = enforced.tag(Stamp.class);
                if (stamp != null) {
                    stamp.commit();
                }
            }
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2).
        }
        return response;
    }

    private static Request enforce(Request request) {
        Request.Builder builder = request.newBuilder();
        // Unconditional: no value survives from anywhere but the engine.
        builder.removeHeader(NAME);
        Stamp stamp = request.tag(Stamp.class);
        if (stamp == null) {
            return builder.build();
        }
        String value = stamp.offer();
        if (value == null) {
            return builder.build();
        }
        // A redirect can move the wire request off TrustedRouter entirely; a
        // self-hosted gateway is not TrustedRouter's to measure (§3.2).
        if ("custom".equals(Telemetry.hostEnum(request.url().toString()))) {
            return builder.build();
        }
        // The wire's own grammar check, independent of whatever produced the
        // value: content-free by construction is only true if it is in
        // grammar, so anything else sends nothing (§2.1, §2.2).
        if (!Telemetry.isWellFormedHeader(value)) {
            return builder.build();
        }
        builder.header(NAME, value);
        return builder.build();
    }
}
