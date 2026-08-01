package com.trustedrouter.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.HttpUrl;

/** PKCE, state, and authorize URL utilities shared by desktop and Android apps. */
public final class OAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private OAuth() {}

    public static String randomState() { return randomState(16); }
    public static String randomState(int byteLength) {
        if (byteLength <= 0) { throw new IllegalArgumentException("byteLength must be positive"); }
        byte[] data = new byte[byteLength];
        RANDOM.nextBytes(data);
        return base64Url(data);
    }

    public static OAuthPkcePair createPkcePair() { return createPkcePair(null); }
    public static OAuthPkcePair createPkcePair(String verifier) {
        String value = verifier;
        if (value == null) {
            byte[] data = new byte[32];
            RANDOM.nextBytes(data);
            value = base64Url(data);
        }
        if (value.length() < 43 || value.length() > 128) {
            throw new IllegalArgumentException("PKCE verifier must be 43 to 128 characters");
        }
        if (!value.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("PKCE verifier contains invalid characters");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new OAuthPkcePair(value, base64Url(digest));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static String authorizeUrl(String controlBaseUrl, OAuthAuthorizeOptions options) {
        if (options.getCodeChallengeMethod() != null && options.getCodeChallenge() == null) {
            throw new IllegalArgumentException(
                    "codeChallenge is required when codeChallengeMethod is set");
        }
        HttpUrl parsed = HttpUrl.get(controlBaseUrl + "/auth");
        HttpUrl.Builder url = parsed.newBuilder();
        String callback = options.getState() == null
                ? options.getCallbackUrl()
                : callbackWithState(options.getCallbackUrl(), options.getState());
        url.addQueryParameter("callback_url", callback);
        add(url, "code_challenge", options.getCodeChallenge());
        add(url, "code_challenge_method", options.getCodeChallengeMethod());
        add(url, "key_label", options.getKeyLabel());
        add(url, "limit", options.getLimit());
        add(url, "usage_limit_type", options.getUsageLimitType());
        add(url, "expires_at", options.getExpiresAt());
        add(url, "spawn_agent", options.getSpawnAgent());
        add(url, "spawn_cloud", options.getSpawnCloud());
        return url.build().toString();
    }

    public static OAuthAuthorization createAuthorization(
            String controlBaseUrl,
            OAuthAuthorizeOptions source,
            String codeVerifier) {
        OAuthPkcePair pkce = createPkcePair(codeVerifier);
        String state = source.getState() == null ? randomState() : source.getState();
        OAuthAuthorizeOptions options = OAuthAuthorizeOptions.builder(source.getCallbackUrl())
                .codeChallenge(pkce.getCodeChallenge())
                .codeChallengeMethod(pkce.getCodeChallengeMethod())
                .keyLabel(source.getKeyLabel())
                .limit(source.getLimit())
                .usageLimitType(source.getUsageLimitType())
                .expiresAt(source.getExpiresAt())
                .spawnAgent(source.getSpawnAgent())
                .spawnCloud(source.getSpawnCloud())
                .state(state)
                .build();
        return new OAuthAuthorization(pkce, state, authorizeUrl(controlBaseUrl, options));
    }

    /** Validates state and extracts a one-time code from an Android or desktop callback URI. */
    public static OAuthCallback parseCallback(String callbackUrl, String expectedState) {
        if (callbackUrl == null || callbackUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("callback URL is required");
        }
        if (expectedState == null || expectedState.isEmpty()) {
            throw new IllegalArgumentException("expected state is required");
        }
        try {
            URI uri = new URI(callbackUrl);
            Map<String, String> query = parseQuery(uri.getRawQuery());
            String state = query.get("state");
            if (!constantTimeEquals(expectedState, state)) {
                throw new IllegalArgumentException("OAuth callback state mismatch");
            }
            String error = query.get("error");
            if (error != null && !error.isEmpty()) {
                String description = query.get("error_description");
                throw new IllegalArgumentException("OAuth authorization failed: "
                        + (description == null || description.isEmpty() ? error : description));
            }
            String code = query.get("code");
            if (code == null || code.isEmpty()) {
                throw new IllegalArgumentException("OAuth callback is missing code");
            }
            return new OAuthCallback(code, state);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid OAuth callback URL", error);
        }
    }

    private static void add(HttpUrl.Builder url, String key, String value) {
        if (value != null && !value.isEmpty()) { url.addQueryParameter(key, value); }
    }

    private static String callbackWithState(String callback, String state) {
        try {
            URI uri = new URI(callback);
            String encoded = java.net.URLEncoder.encode(state, "UTF-8").replace("+", "%20");
            String query = uri.getRawQuery();
            StringBuilder updated = new StringBuilder();
            if (query != null && !query.isEmpty()) {
                for (String item : query.split("&")) {
                    if (!item.startsWith("state=")) {
                        if (updated.length() > 0) { updated.append('&'); }
                        updated.append(item);
                    }
                }
            }
            if (updated.length() > 0) { updated.append('&'); }
            updated.append("state=").append(encoded);
            StringBuilder result = new StringBuilder();
            result.append(uri.getScheme()).append(':');
            if (uri.getRawAuthority() != null) {
                result.append("//").append(uri.getRawAuthority());
            }
            if (uri.getRawPath() != null) {
                result.append(uri.getRawPath());
            }
            result.append('?').append(updated);
            if (uri.getRawFragment() != null) {
                result.append('#').append(uri.getRawFragment());
            }
            return new URI(result.toString()).toASCIIString();
        } catch (URISyntaxException | java.io.UnsupportedEncodingException error) {
            throw new IllegalArgumentException("invalid callback URL", error);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) { return values; }
        for (String item : rawQuery.split("&")) {
            int separator = item.indexOf('=');
            String key = separator < 0 ? item : item.substring(0, separator);
            String value = separator < 0 ? "" : item.substring(separator + 1);
            try {
                String decodedKey = URLDecoder.decode(key, "UTF-8");
                if (values.containsKey(decodedKey)) {
                    throw new IllegalArgumentException(
                            "OAuth callback contains duplicate " + decodedKey + " parameter");
                }
                values.put(decodedKey, URLDecoder.decode(value, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 unavailable", impossible);
            }
        }
        return values;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) { return false; }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
