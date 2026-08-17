package com.trustedrouter.internal;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
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
 * put it there), then re-stamps the engine's validated value on at most one
 * header-write attempt per engine attempt, and only while the wire host is
 * still a TrustedRouter host and the value is still in grammar.
 *
 * <p>The engine's value travels as a request TAG rather than being trusted
 * from the inbound header, because OkHttp preserves tags across its follow-up
 * rebuilds. Both the enforcer and its tag type are inaccessible outside this
 * internal package, and {@link RequestFactory}'s minting path is package-only,
 * so an interceptor on an injected client cannot forge a stamp either.
 *
 * <p>OkHttp's {@link EventListener#requestHeadersStart(Call)} is the last
 * public phase signal before its codec starts writing request headers. The
 * listener spends an armed stamp there, not only after a response: if a peer
 * resets after some request bytes but before response headers, OkHttp may
 * recover inside the same {@code Call}, and the recovered exchange must not
 * repeat the same attempt index. A failure before this callback (DNS,
 * connect, or exchange setup) leaves the stamp available. The SDK listener
 * is composed with, rather than substituted for, the injected client's
 * listener.
 *
 * <p>Telemetry never fails a request (&sect;2.2): every branch here is wrapped
 * so a failure degrades to sending no header rather than breaking the call.
 */
final class ReservedHeader implements Interceptor {
    /** The SDK-reserved header name, matched case-insensitively by OkHttp. */
    static final String NAME = "x-tr-client";

    /**
     * The engine's intended header value for one attempt, carried as a request
     * tag so it survives OkHttp's follow-up rebuilds, plus synchronized state
     * so only the first header-write attempt is stamped.
     *
     * <p>OkHttp re-sends a {@code 503}/{@code 408} by handing back the very
     * same {@code Request} instance and builds a redirect with
     * {@code request.newBuilder()}; both preserve tags, so one instance sees
     * every wire request derived from one engine attempt.
     */
    private static final class Stamp {
        private final String value;
        private boolean offered;
        private boolean committed;

        /**
         * Engine-only: deliberately NOT public. A public constructor would
         * just move the forgery channel from the header to the tag — an
         * interceptor on an injected client could attach its own Stamp and
         * have the enforcer emit it, even for an opted-out client. Only
         * {@code RequestFactory}, in this package, mints one.
         */
        private Stamp(String value) {
            this.value = value;
        }

        /**
         * The value for a wire request that is about to write headers, or null
         * once this attempt has already spent its label.
         *
         * <p>Deliberately not a network-interceptor pass counter: OkHttp can
         * re-run that interceptor for a recoverable failure before request
         * writing starts. Offering only arms the stamp; the event listener
         * commits it at the write boundary.
         */
        synchronized String offer() {
            if (committed) {
                return null;
            }
            offered = true;
            return value;
        }

        /**
         * Marks an offered label spent. Called immediately before OkHttp asks
         * its codec to write request headers; also called idempotently after a
         * response as a defensive fallback for direct interceptor chains.
         */
        synchronized void commitOffered() {
            if (offered) {
                committed = true;
            }
        }
    }

    /**
     * Installs the wire enforcer and composes its phase marker with the
     * caller's listener factory. Installation is idempotent for a client that
     * already came from another {@code RequestFactory}; per-call clients copy
     * both pieces through {@code newBuilder()}.
     */
    static OkHttpClient install(OkHttpClient base) {
        boolean hasEnforcer = false;
        for (Interceptor interceptor : base.networkInterceptors()) {
            if (interceptor instanceof ReservedHeader) {
                hasEnforcer = true;
                break;
            }
        }
        boolean hasMarker = base.eventListenerFactory() instanceof StampListenerFactory;
        if (hasEnforcer && hasMarker) {
            return base;
        }
        OkHttpClient.Builder builder = base.newBuilder();
        if (!hasMarker) {
            builder.eventListenerFactory(
                    new StampListenerFactory(base.eventListenerFactory()));
        }
        if (!hasEnforcer) {
            builder.addNetworkInterceptor(new ReservedHeader());
        }
        return builder.build();
    }

    /** Mints the opaque engine-only request tag. */
    static void stamp(Request.Builder request, String value) {
        request.tag(Stamp.class, new Stamp(value));
    }

    private static final class StampListenerFactory implements EventListener.Factory {
        private final EventListener.Factory callerFactory;

        private StampListenerFactory(EventListener.Factory callerFactory) {
            this.callerFactory = callerFactory;
        }

        @Override
        public EventListener create(Call call) {
            EventListener caller = callerFactory.create(call);
            Stamp stamp = call.request().tag(Stamp.class);
            if (stamp == null) {
                return caller;
            }
            // Marker first: even a broken caller callback cannot reopen the
            // duplicate-index window. The caller still receives the callback
            // with exactly the same aggregate semantics OkHttp documents.
            return new StampEventListener(stamp).plus(caller);
        }
    }

    private static final class StampEventListener extends EventListener {
        private final Stamp stamp;

        private StampEventListener(Stamp stamp) {
            this.stamp = stamp;
        }

        @Override
        public void requestHeadersStart(Call call) {
            try {
                stamp.commitOffered();
            } catch (RuntimeException impossible) {
                // Telemetry never fails a request (§2.2).
            }
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Request enforced;
        try {
            enforced = enforce(request);
        } catch (RuntimeException impossible) {
            // Telemetry never fails a request (§2.2), and a failed telemetry
            // path must fail closed rather than forwarding a caller-forged
            // reserved value.
            enforced = request.newBuilder().removeHeader(NAME).build();
        }
        Response response = chain.proceed(enforced);
        // Defensive fallback for direct/custom interceptor chains that do not
        // drive OkHttp's EventListener. On a real Call the write-start event
        // already committed this idempotently; if proceed() throws before
        // that event, this path is not reached and pre-send recovery keeps the
        // label.
        try {
            if (enforced.header(NAME) != null) {
                Stamp stamp = enforced.tag(Stamp.class);
                if (stamp != null) {
                    stamp.commitOffered();
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
        // A redirect can move the wire request off TrustedRouter entirely; a
        // self-hosted gateway is not TrustedRouter's to measure (§3.2).
        if ("custom".equals(Telemetry.hostEnum(request.url().toString()))) {
            return builder.build();
        }
        // The wire's own grammar check, independent of whatever produced the
        // value: content-free by construction is only true if it is in
        // grammar, so anything else sends nothing (§2.1, §2.2).
        if (!Telemetry.isWellFormedHeader(stamp.value)) {
            return builder.build();
        }
        String value = stamp.offer();
        if (value == null) {
            return builder.build();
        }
        builder.header(NAME, value);
        return builder.build();
    }
}
