package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.trustedrouter.errors.AuthenticationException;
import com.trustedrouter.errors.BadRequestException;
import com.trustedrouter.errors.EndpointNotSupportedException;
import com.trustedrouter.errors.InternalException;
import com.trustedrouter.errors.NotFoundException;
import com.trustedrouter.errors.PermissionDeniedException;
import com.trustedrouter.errors.RateLimitException;
import com.trustedrouter.errors.TrustedRouterException;
import java.io.IOException;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * L6 error taxonomy: status-to-exception classification, response decode and
 * raise helpers, and retry-after parsing. Internal class with no
 * compatibility guarantees. The single copy in this SDK — oauth and
 * attestation reuse it through {@code Transport}'s delegates.
 */
public final class ErrorClassifier {
    private ErrorClassifier() {}

    /** Decodes a JSON body, raising the typed error for non-2xx responses. */
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

    /** Raises the typed error for non-2xx responses, leaving 2xx bodies open. */
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

    /** Maps a status to the typed exception, preserving the raw payload. */
    public static TrustedRouterException classify(
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
     * Ceiling on a server-supplied Retry-After floor, in seconds.
     *
     * <p>60s is above any hint a healthy gateway sends and far below the point
     * where a caller would rather have the error. Matches
     * {@code MAX_RETRY_AFTER_SECONDS} in the Python and JS SDKs,
     * {@code MaxRetryAfterSeconds} in Go and {@code MAX_RETRY_AFTER} in Rust.
     */
    public static final double MAX_RETRY_AFTER_SECONDS = 60.0d;

    /**
     * Parses the server's requested wait in seconds, or null when it did not
     * say. Read from live headers only, so it must run BEFORE the response is
     * closed by the engine.
     */
    public static Double retryAfterSeconds(Response response) {
        // retry-after-ms wins when both are present: it is the more precise of
        // the two, and a server that sends it means the sub-second value.
        String millis = response.header("retry-after-ms");
        if (millis != null) {
            try {
                Double bounded = boundedRetryAfter(Double.parseDouble(millis.trim()) / 1000.0d);
                if (bounded != null) {
                    return bounded;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to Retry-After rather than poison the backoff.
            }
        }
        String value = response.header("Retry-After");
        if (value == null) {
            return null;
        }
        try {
            return boundedRetryAfter(Double.parseDouble(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Clamps a parsed Retry-After hint into {@code [0, MAX_RETRY_AFTER_SECONDS]},
     * or rejects it.
     *
     * <p>Retry-After arrives from whatever answered the socket &mdash; the
     * gateway, a proxy in front of it, an alias domain &mdash; so it is
     * untrusted input, and it was applied as an <em>uncapped</em> floor on the
     * backoff sleep. {@link Double#parseDouble} accepts the literals
     * {@code "Infinity"}, {@code "+Infinity"} and {@code "NaN"} per the
     * {@code Double.valueOf} grammar, and JLS 5.1.3 narrowing from double to
     * long <em>saturates</em> rather than throwing: {@code +Infinity} and any
     * value above the long range become {@code Long.MAX_VALUE}. That reaches
     * {@code Thread.sleep} as roughly 292 million years. A plain
     * {@code Retry-After: 100000} needed no exotic behaviour at all &mdash; it
     * parks the caller for 27.8 hours per attempt.
     *
     * <p>Returns null for anything that is not a usable delay, so the caller
     * falls through to plain jittered backoff. The rejected set matches the
     * Python, JS, Go and Rust SDKs so every SDK accepts the same header
     * language.
     *
     * @param seconds the parsed hint
     * @return the clamped hint, or null when it is unusable
     */
    public static Double boundedRetryAfter(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0.0d) {
            return null;
        }
        return Double.valueOf(Math.min(seconds, MAX_RETRY_AFTER_SECONDS));
    }
}
