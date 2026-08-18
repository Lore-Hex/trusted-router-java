package com.trustedrouter.internal;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Prevents OkHttp's status-driven follow-ups from turning one SDK attempt into
 * multiple physical requests.
 *
 * <p>{@code retryOnConnectionFailure(false)} covers connection recovery and
 * 408 handling, but OkHttp 5 still repeats a 503 carrying
 * {@code Retry-After: 0}; a 421 on a coalesced HTTP/2 connection can also be
 * replayed inside {@code RetryAndFollowUpInterceptor}. Redirects and
 * authenticators have public disable switches, while these status follow-ups
 * do not.
 *
 * <p>The network interceptor temporarily presents those three statuses to
 * OkHttp's internal follow-up layer as a neutral 599. The outer application
 * interceptor restores the exact original status, message, request and nested
 * response chain before any caller interceptor or SDK code observes it.
 * Headers and the live response body are never changed. The opaque marker is a
 * private request tag, so it is neither sent on the wire nor forgeable by a
 * caller.
 */
final class PhysicalAttemptGuard {
    private static final int NEUTRAL_STATUS = 599;

    private PhysicalAttemptGuard() {}

    static OkHttpClient install(OkHttpClient base) {
        boolean hasRestorer = false;
        for (Interceptor interceptor : base.interceptors()) {
            if (interceptor instanceof RestoreResponse) {
                hasRestorer = true;
                break;
            }
        }
        boolean hasGuard = false;
        for (Interceptor interceptor : base.networkInterceptors()) {
            if (interceptor instanceof GuardNetworkResponse) {
                hasGuard = true;
                break;
            }
        }
        if (hasRestorer && hasGuard) {
            return base;
        }
        OkHttpClient.Builder builder = base.newBuilder();
        // Existing caller application interceptors wrap the restorer and
        // therefore see only the restored response. The network guard goes
        // FIRST so caller network interceptors still observe the server's
        // real response while the guard neutralizes it only on the final
        // unwind into OkHttp's private follow-up layer.
        if (!hasRestorer) {
            builder.addInterceptor(new RestoreResponse());
        }
        if (!hasGuard) {
            builder.networkInterceptors().add(0, new GuardNetworkResponse());
        }
        return builder.build();
    }

    private static boolean canTriggerStatusFollowUp(int code) {
        return code == 408 || code == 421 || code == 503;
    }

    private static final class OriginalResponse {
        private final Request request;
        private final int code;
        private final String message;

        private OriginalResponse(Response response) {
            this.request = response.request();
            this.code = response.code();
            this.message = response.message();
        }
    }

    private static final class GuardNetworkResponse implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            if (!canTriggerStatusFollowUp(response.code())) {
                return response;
            }
            OriginalResponse original = new OriginalResponse(response);
            Request marked = response.request().newBuilder()
                    .tag(OriginalResponse.class, original)
                    .build();
            return response.newBuilder()
                    .request(marked)
                    .code(NEUTRAL_STATUS)
                    .message("TrustedRouter physical-attempt guard")
                    .build();
        }
    }

    private static final class RestoreResponse implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            return restore(chain.proceed(chain.request()));
        }

        private static Response restore(Response response) {
            if (response == null) {
                return null;
            }
            OriginalResponse direct = response.request().tag(OriginalResponse.class);
            OriginalResponse original = direct == null ? findOriginal(response) : direct;
            Response network = restore(response.networkResponse());
            Response cache = restore(response.cacheResponse());
            Response prior = restore(response.priorResponse());
            if (original == null
                    && network == response.networkResponse()
                    && cache == response.cacheResponse()
                    && prior == response.priorResponse()) {
                return response;
            }
            Response.Builder restored = response.newBuilder();
            if (original != null) {
                restored.code(original.code)
                        .message(original.message);
                // Only the network-layer response itself carries the marker.
                // An outer cache/bridge response keeps its own original
                // request while inheriting the restored status/message.
                if (direct != null) {
                    restored.request(original.request);
                }
            }
            if (network != response.networkResponse()) {
                restored.networkResponse(network);
            }
            if (cache != response.cacheResponse()) {
                restored.cacheResponse(cache);
            }
            if (prior != response.priorResponse()) {
                restored.priorResponse(prior);
            }
            return restored.build();
        }

        private static OriginalResponse findOriginal(Response response) {
            if (response == null) {
                return null;
            }
            OriginalResponse original = response.request().tag(OriginalResponse.class);
            if (original != null) {
                return original;
            }
            original = findOriginal(response.networkResponse());
            if (original != null) {
                return original;
            }
            original = findOriginal(response.cacheResponse());
            return original != null ? original : findOriginal(response.priorResponse());
        }
    }
}
