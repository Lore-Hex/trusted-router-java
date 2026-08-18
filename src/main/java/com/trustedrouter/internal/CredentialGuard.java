package com.trustedrouter.internal;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Final wire guard for requests that must remain credential-free.
 *
 * <p>Request assembly removes SDK defaults, but an interceptor on an injected
 * OkHttp client runs afterwards and can add them again. A private request tag
 * carries the SDK's decision to this last network interceptor, which removes
 * ambient credentials immediately before bytes are written.
 */
final class CredentialGuard implements Interceptor {
    private static final class CredentialFree {
        private CredentialFree() {}
    }

    static OkHttpClient install(OkHttpClient base) {
        for (Interceptor interceptor : base.networkInterceptors()) {
            if (interceptor instanceof CredentialGuard) {
                return base;
            }
        }
        return base.newBuilder().addNetworkInterceptor(new CredentialGuard()).build();
    }

    static void markCredentialFree(Request.Builder request) {
        request.tag(CredentialFree.class, new CredentialFree());
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (request.tag(CredentialFree.class) == null) {
            return chain.proceed(request);
        }
        Request guarded = request.newBuilder()
                .removeHeader("Authorization")
                .removeHeader("Proxy-Authorization")
                .removeHeader("Cookie")
                .removeHeader("Cookie2")
                .removeHeader("X-Api-Key")
                .removeHeader("X-TrustedRouter-Workspace")
                .removeHeader("Idempotency-Key")
                .removeHeader(ReservedHeader.NAME)
                .build();
        return chain.proceed(guarded);
    }
}
