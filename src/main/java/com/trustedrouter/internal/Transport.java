package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouter;
import com.trustedrouter.TrustedRouterOptions;
import com.trustedrouter.errors.AuthenticationException;
import com.trustedrouter.errors.BadRequestException;
import com.trustedrouter.errors.EndpointNotSupportedException;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.NotFoundException;
import com.trustedrouter.errors.PermissionDeniedException;
import com.trustedrouter.errors.RateLimitException;
import com.trustedrouter.errors.TrustedRouterException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Single implementation of auth, plane routing, retry, and error semantics. */
public final class Transport {
    public enum Plane { INFERENCE, CONTROL }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final List<String> inferenceBaseUrls;
    private final String controlBaseUrl;
    private final OkHttpClient client;
    private final Long timeoutMillis;
    private final Map<String, String> headers;
    private final String workspaceId;
    private final int maxRetries;
    private final boolean regionalFailover;

    public Transport(TrustedRouterOptions options) {
        this.apiKey = options.getApiKey();
        this.baseUrl = options.getBaseUrl();
        this.controlBaseUrl = options.getControlBaseUrl();
        OkHttpClient configured = options.getHttpClient();
        this.client = configured == null ? new OkHttpClient() : configured;
        this.timeoutMillis = options.getTimeoutMillis();
        this.headers = options.getHeaders();
        this.workspaceId = options.getWorkspaceId();
        this.maxRetries = options.getMaxRetries();
        this.regionalFailover = options.isRegionalFailover();
        this.inferenceBaseUrls = inferenceBaseUrls(this.baseUrl, this.regionalFailover);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getControlBaseUrl() {
        return controlBaseUrl;
    }

    /**
     * Primary first, then the alias domains.
     *
     * <p>This list must have MORE THAN ONE entry or failover cannot engage at
     * all: every advance below is guarded by {@code baseIndex + 1 < size}. Until
     * the aliases existed there was only ever one candidate, so the retry loop
     * could do nothing but re-send to the host that had just failed —
     * {@code regionalFailover} widened which statuses were retried, never where.
     *
     * <p>Aliases are appended only for the default API host. A caller who passed
     * a base URL of their own — a private deployment, a test server, a regional
     * pin — gets exactly that; silently redirecting their traffic to a public
     * alias would be worse than failing.
     */
    public static List<String> inferenceBaseUrls(
            String primaryBaseUrl, boolean regionalFailover) {
        // Both sides go through the same normalization: comparing a stored base
        // URL against the raw constant is how this silently degrades to one entry.
        String primary = withoutTrailingSlashes(primaryBaseUrl);
        List<String> urls = new ArrayList<String>();
        urls.add(primary);
        if (!regionalFailover
                || !primary.equals(withoutTrailingSlashes(TrustedRouter.DEFAULT_API_BASE_URL))) {
            return Collections.unmodifiableList(urls);
        }
        for (String alias : TrustedRouter.ALIAS_API_BASE_URLS) {
            String normalized = withoutTrailingSlashes(alias);
            if (!urls.contains(normalized)) {
                urls.add(normalized);
            }
        }
        return Collections.unmodifiableList(urls);
    }

    private static String withoutTrailingSlashes(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public Response execute(
            Plane plane,
            String method,
            String path,
            JsonElement body,
            CallOptions options,
            boolean streaming) throws TrustedRouterException {
        boolean inference = plane == Plane.INFERENCE;
        List<String> bases = inference
                ? inferenceBaseUrls : Collections.singletonList(controlBaseUrl);
        return executeUrls(
                joinAll(bases, path), method, body, normalize(options), streaming,
                inference ? regionalFailover : true, true);
    }

    public Response executeAbsolute(String url, String method, boolean streaming)
            throws TrustedRouterException {
        return executeUrls(
                Collections.singletonList(url), method, null, CallOptions.NONE, streaming,
                true, false);
    }

    private Response executeUrls(
            List<String> urls,
            String method,
            JsonElement body,
            CallOptions options,
            boolean streaming,
            boolean allowRegionalFailover,
            boolean includeCredentials) throws TrustedRouterException {
        int attempt = 0;
        int baseIndex = 0;
        while (true) {
            String url = urls.get(baseIndex);
            try {
                Response response = requestClient(options, streaming)
                        .newCall(buildRequest(url, method, body, options, includeCredentials))
                        .execute();
                int status = response.code();
                if (attempt >= maxRetries || !retryable(status, allowRegionalFailover)) {
                    return response;
                }
                Double retryAfter = retryAfterSeconds(response);
                response.close();
                if (failoverable(status) && baseIndex + 1 < urls.size()) {
                    baseIndex++;
                }
                sleepBeforeRetry(attempt, retryAfter);
                attempt++;
            } catch (IOException error) {
                if (attempt >= maxRetries || !allowRegionalFailover) {
                    throw new InternalException(
                            503,
                            "TrustedRouter endpoint unavailable: " + error.getMessage(),
                            null,
                            error);
                }
                // A connection failure means no server read the request, so
                // another domain cannot double-execute it.
                if (baseIndex + 1 < urls.size()) {
                    baseIndex++;
                }
                sleepBeforeRetry(attempt, null);
                attempt++;
            }
        }
    }

    private OkHttpClient requestClient(CallOptions options, boolean streaming) {
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

    private Request buildRequest(
            String url,
            String method,
            JsonElement body,
            CallOptions options,
            boolean includeCredentials) {
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

    private static CallOptions normalize(CallOptions options) {
        return options == null ? CallOptions.NONE : options;
    }

    public static JsonElement decodeJson(Response response) throws TrustedRouterException {
        try {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JsonElement payload = JsonSupport.parseOrNull(text);
            if (!response.isSuccessful()) {
                throw classify(response.code(), JsonSupport.errorMessage(payload), payload, response);
            }
            if (payload == null) {
                throw new InternalException(502, "TrustedRouter returned an empty JSON response", null);
            }
            return payload;
        } catch (TrustedRouterException error) {
            throw error;
        } catch (IOException error) {
            throw new InternalException(503, "TrustedRouter response read failed: " + error.getMessage(), null, error);
        } finally {
            response.close();
        }
    }

    public static void requireSuccess(Response response) throws TrustedRouterException {
        if (response.isSuccessful()) {
            return;
        }
        JsonElement payload = null;
        try {
            ResponseBody body = response.body();
            payload = JsonSupport.parseOrNull(body == null ? "" : body.string());
        } catch (IOException ignored) {
            // The status still classifies the failure safely.
        }
        TrustedRouterException error = classify(
                response.code(), JsonSupport.errorMessage(payload), payload, response);
        response.close();
        throw error;
    }

    private static TrustedRouterException classify(
            int status, String message, JsonElement payload, Response response) {
        if (status == 401) {
            return new AuthenticationException(status, message, payload);
        }
        if (status == 403) {
            return new PermissionDeniedException(status, message, payload);
        }
        if (status == 404) {
            return new NotFoundException(status, message, payload);
        }
        if (status == 429) {
            return new RateLimitException(status, message, payload, retryAfterSeconds(response));
        }
        if (status == 501) {
            return new EndpointNotSupportedException(status, message, payload);
        }
        if (status >= 400 && status < 500) {
            return new BadRequestException(status, message, payload);
        }
        if (status >= 500) {
            return new InternalException(status, message, payload);
        }
        return new TrustedRouterException(status, message, payload);
    }

    /**
     * Which statuses may move a request to a different domain. Deliberately
     * narrower than {@link #retryable}: a 500 means a server accepted the
     * request and failed inside it, so an inference call may already have run
     * and been billed. Re-sending that to a second domain risks charging twice.
     * Only statuses that mean "nothing processed this" move hosts.
     */
    private static boolean failoverable(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static boolean retryable(int status, boolean regionalFailover) {
        if (status == 429) {
            return true;
        }
        if (status == 502 || status == 503 || status == 504) {
            return regionalFailover;
        }
        return status >= 500;
    }

    private static Double retryAfterSeconds(Response response) {
        String value = response.header("Retry-After");
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(Math.max(0.0d, Double.parseDouble(value.trim())));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void sleepBeforeRetry(int attempt, Double retryAfter)
            throws InternalException {
        int bounded = Math.min(6, Math.max(0, attempt));
        long ceiling = Math.min(30_000L, 500L * (1L << bounded));
        long delay = ceiling == 0L ? 0L : ThreadLocalRandom.current().nextLong(ceiling + 1L);
        if (retryAfter != null) {
            delay = Math.max(delay, (long) (retryAfter.doubleValue() * 1000.0d));
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("retry interrupted");
            interrupted.initCause(error);
            throw new InternalException(503, interrupted.getMessage(), null, interrupted);
        }
    }

    private static List<String> joinAll(List<String> baseUrls, String path) {
        List<String> urls = new ArrayList<String>(baseUrls.size());
        for (String base : baseUrls) {
            // Validates the path against every candidate before a byte is sent,
            // so a rejected path is rejected outright and never half-attempted.
            urls.add(joinUrl(base, path));
        }
        return urls;
    }

    private static String joinUrl(String base, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("API path is required");
        }
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) {
            throw new IllegalArgumentException("API path must be relative to its configured plane");
        }
        return base + "/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private static boolean requiresRequestBody(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private static String userAgent() {
        return "trusted-router-java/" + TrustedRouter.VERSION
                + " java/" + System.getProperty("java.version", "unknown")
                + " " + System.getProperty("os.name", "unknown");
    }
}
