package com.trustedrouter.internal;

import com.trustedrouter.TrustedRouter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * L2 plane router: builds the ordered base-URL candidate list once per
 * logical call. Internal class with no compatibility guarantees.
 *
 * <p>The inference plane gets the primary first plus the alias domains; the
 * control plane and absolute metadata fetches get a singleton list, so
 * failover is structurally impossible there — list LENGTH is the gate, not a
 * second flag.
 */
public final class CandidateUrls {
    private CandidateUrls() {}

    /**
     * Primary first, then the alias domains.
     *
     * <p>This list must have MORE THAN ONE entry or failover cannot engage at
     * all: every advance in the engine is guarded by
     * {@code baseIndex + 1 < size}. Until the aliases existed there was only
     * ever one candidate, so the retry loop could do nothing but re-send to
     * the host that had just failed — {@code regionalFailover} widened which
     * statuses were retried, never where.
     *
     * <p>Aliases are appended only for the default API host. A caller who
     * passed a base URL of their own — a private deployment, a test server, a
     * regional pin — gets exactly that; silently redirecting their traffic to
     * a public alias would be worse than failing.
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

    /** Joins one path onto every candidate, validating before a byte is sent. */
    public static List<String> joinAll(List<String> baseUrls, String path) {
        List<String> urls = new ArrayList<String>(baseUrls.size());
        for (String base : baseUrls) {
            // Validates the path against every candidate before a byte is sent,
            // so a rejected path is rejected outright and never half-attempted.
            urls.add(joinUrl(base, path));
        }
        return urls;
    }

    /** Joins a relative path onto a base URL, rejecting absolute targets. */
    public static String joinUrl(String base, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("API path is required");
        }
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) {
            throw new IllegalArgumentException("API path must be relative to its configured plane");
        }
        return base + "/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private static String withoutTrailingSlashes(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
