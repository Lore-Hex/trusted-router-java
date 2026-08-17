package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouter;
import com.trustedrouter.TrustedRouterOptions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * L4 attempt assembly: per-attempt request construction. Internal class with
 * no compatibility guarantees.
 *
 * <p>Owns default and per-call headers, Bearer/workspace override-and-remove
 * with empty-string suppression, User-Agent, credential stripping for
 * absolute metadata fetches, Idempotency-Key stamping, empty-body
 * POST/PUT/PATCH, and streaming-vs-buffered timeout shaping. It builds one
 * {@link Request} per attempt from immutable inputs and never decides retry
 * or failover — that is the engine's job.
 *
 * <p>This class is also the SDK's single idempotency-key generator: the key
 * is minted ONCE per logical call BEFORE the attempt loop (by the endpoints
 * that opt in today) and replayed verbatim on every attempt and every domain
 * move, so the caller is never double-charged.
 */
public final class RequestFactory {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final OkHttpClient client;
    private final Long timeoutMillis;
    private final Map<String, String> headers;
    private final String workspaceId;

    public RequestFactory(TrustedRouterOptions options) {
        this.apiKey = options.getApiKey();
        OkHttpClient configured = options.getHttpClient();
        this.client = configured == null ? new OkHttpClient() : configured;
        this.timeoutMillis = options.getTimeoutMillis();
        this.headers = options.getHeaders();
        this.workspaceId = options.getWorkspaceId();
    }

    /**
     * Returns options carrying an idempotency key, minting one only when the
     * caller did not supply one. Called once per logical call, before the
     * attempt loop, by exactly the endpoints that opt in today — stamping
     * GETs or control-plane reads would change pinned wire behaviour.
     */
    public static CallOptions ensureIdempotencyKey(CallOptions options) {
        CallOptions value = options == null ? CallOptions.NONE : options;
        if (value.getIdempotencyKey() != null && !value.getIdempotencyKey().isEmpty()) {
            return value;
        }
        return value.toBuilder().idempotencyKey(newIdempotencyKey()).build();
    }

    /** The single key-generator helper in this SDK. */
    public static String newIdempotencyKey() {
        return "tr-req-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Shapes timeouts for this call: buffered requests get a single call
     * timeout; stream-opens get per-phase connect/read/write timeouts with
     * the call timeout disabled so a long-lived stream is not killed.
     */
    public OkHttpClient requestClient(CallOptions options, boolean streaming) {
        Long timeout = optionsTimeout(options);
        if (timeout == null) {
            return client;
        }
        OkHttpClient.Builder builder = client.newBuilder();
        if (streaming) {
            builder.connectTimeout(timeout.longValue(), TimeUnit.MILLISECONDS);
            builder.readTimeout(timeout.longValue(), TimeUnit.MILLISECONDS);
            builder.writeTimeout(timeout.longValue(), TimeUnit.MILLISECONDS);
            builder.callTimeout(0L, TimeUnit.MILLISECONDS);
        } else {
            builder.callTimeout(timeout.longValue(), TimeUnit.MILLISECONDS);
        }
        return builder.build();
    }

    /**
     * Assembles one attempt's request; pure function of its arguments.
     *
     * @param telemetryHeader the per-attempt {@code x-tr-client} value from
     *     the engine's recorder, or null to send no telemetry header — the
     *     engine passes null for control-plane calls, absolute fetches,
     *     custom hosts, opted-out clients, and out-of-grammar values
     */
    public Request buildRequest(
            String url,
            String method,
            JsonElement body,
            CallOptions options,
            boolean includeCredentials,
            String telemetryHeader) {
        Request.Builder request = new Request.Builder().url(url);
        if (includeCredentials) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
            for (Map.Entry<String, String> header : options.getHeaders().entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
        }
        request.header("User-Agent", userAgent());
        // x-tr-client is SDK-reserved across all six TrustedRouter SDKs: a
        // caller-supplied value is stripped on EVERY path (OkHttp header
        // names are case-insensitive), so the opt-out, custom-host,
        // control-plane, and absolute-fetch exclusions are unconditional.
        // Only an active recorder's validated value may ride the wire.
        request.removeHeader("x-tr-client");
        if (telemetryHeader != null) {
            request.header("x-tr-client", telemetryHeader);
        }

        String idempotencyKey = options.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            request.header("Idempotency-Key", idempotencyKey);
        }

        if (includeCredentials) {
            String callWorkspace = options.hasWorkspaceIdOverride()
                    ? options.getWorkspaceId() : workspaceId;
            if (callWorkspace != null && !callWorkspace.isEmpty()) {
                request.header("X-TrustedRouter-Workspace", callWorkspace);
            } else if (options.hasWorkspaceIdOverride()) {
                request.removeHeader("X-TrustedRouter-Workspace");
            }
            String callKey = options.hasApiKeyOverride() ? options.getApiKey() : apiKey;
            if (callKey != null && !callKey.isEmpty()) {
                request.header("Authorization", "Bearer " + callKey);
            } else if (options.hasApiKeyOverride()) {
                request.removeHeader("Authorization");
            }
        } else {
            // Absolute metadata fetches intentionally inherit no caller headers.
            request.removeHeader("Authorization");
            request.removeHeader("Proxy-Authorization");
            request.removeHeader("Cookie");
            request.removeHeader("X-TrustedRouter-Workspace");
            request.removeHeader("Idempotency-Key");
        }

        RequestBody requestBody = body == null
                ? null
                : RequestBody.Companion.create(JsonSupport.GSON.toJson(body), JSON);
        if (requestBody == null && requiresRequestBody(method)) {
            requestBody = RequestBody.Companion.create(new byte[0], JSON);
        }
        request.method(method, requestBody);
        return request.build();
    }

    private Long optionsTimeout(CallOptions options) {
        return options.hasTimeoutOverride() ? options.getTimeoutMillis() : timeoutMillis;
    }

    private static boolean requiresRequestBody(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    /**
     * The User-Agent the telemetry contract's enclave parser accepts
     * (contract §3.1): {@code trusted-router-java/SEMVER( runtime/ver)?}.
     * The previous form appended {@code os.name} ("Mac OS X"), which fails
     * that grammar and downgraded every request's identity to unparsed;
     * OS/arch never belonged in the UA (§3.2: static identity is UA-only,
     * and the closed OS enum is beacon vocabulary).
     */
    static String userAgent() {
        String runtime = runtimeToken(System.getProperty("java.version", ""));
        if (runtime.isEmpty()) {
            return "trusted-router-java/" + TrustedRouter.VERSION;
        }
        return "trusted-router-java/" + TrustedRouter.VERSION + " java/" + runtime;
    }

    /**
     * Sanitizes {@code java.version} into the contract's runtime grammar
     * {@code [0-9A-Za-z.+-]{1,24}} — legacy versions like {@code 1.8.0_452}
     * carry an underscore, which the grammar forbids.
     */
    static String runtimeToken(String version) {
        String value = version == null ? "" : version;
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < value.length() && token.length() < 24; index++) {
            char c = value.charAt(index);
            if (c == '_') {
                c = '-';
            }
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || c == '.' || c == '+' || c == '-';
            if (allowed) {
                token.append(c);
            }
        }
        return token.toString();
    }
}
