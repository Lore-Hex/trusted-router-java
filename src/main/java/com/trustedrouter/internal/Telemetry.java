package com.trustedrouter.internal;

import com.trustedrouter.TrustedRouter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import okhttp3.HttpUrl;

/**
 * L7 telemetry vocabulary and pure helpers for the client-observed
 * reliability contract (docs/client-telemetry.md in Lore-Hex/quill-router,
 * contract v1). Internal class with no compatibility guarantees.
 *
 * <p>This SDK implements only the HEADER channel of the contract
 * (&sect;3.2 {@code x-tr-client}): per-attempt facts ride the request the
 * caller already made. The beacon channel (&sect;4/&sect;5) is deliberately
 * NOT implemented here per &sect;9 step 7 and &sect;10 — no beacons in a
 * second SDK until the Python contract has been live and calibrated. The
 * enum lists and beacon path below are pinned anyway (&sect;6.4) so the
 * vocabulary cannot drift before that later PR.
 *
 * <p>Everything here is content-free by construction (&sect;2.1): closed
 * enums, anchored regexes, hard length caps. Telemetry never fails a request
 * (&sect;2.2): out-of-grammar values send nothing and never throw.
 */
public final class Telemetry {
    private Telemetry() {}

    /** Beacon schema version, pinned for the later beacon PR (&sect;5.1). */
    public static final int SCHEMA_VERSION = 1;

    /** Beacon ingest path on the control plane, pinned (&sect;4). */
    public static final String CLIENT_EVENTS_PATH = "/client-events";

    /** Hard cap on every duration on the wire, in milliseconds (&sect;3.2). */
    public static final long MAX_DURATION_MS = 3_600_000L;

    /** Host vocabulary (&sect;5.2), pinned by {@code TelemetryParityTest}. */
    public static final List<String> HOSTS = immutable(
            "apex", "ally", "uptime", "us_central1", "us_east4", "europe_west4",
            "control", "custom");

    /** Endpoint vocabulary (&sect;5.2), pinned by {@code TelemetryParityTest}. */
    public static final List<String> ENDPOINTS = immutable(
            "chat_completions", "messages", "responses", "embeddings", "images",
            "videos", "models", "fusion", "control_other", "inference_other");

    /** Outcome vocabulary (&sect;5.2), pinned by {@code TelemetryParityTest}. */
    public static final List<String> OUTCOMES = immutable(
            "ok", "http_error", "transport_error", "timeout", "stream_broken",
            "aborted");

    /** Error-class vocabulary (&sect;5.2), pinned by {@code TelemetryParityTest}. */
    public static final List<String> ERROR_CLASSES = immutable(
            "dns", "tls", "connect_refused", "connect_timeout", "connect_error",
            "read_timeout", "write_timeout", "pool_timeout", "protocol_error",
            "reset", "io_error", "proxy_error", "stream_stalled", "unknown");

    /**
     * Regional inference bases (&sect;5.2 host map). This SDK has no regional
     * pinning surface yet, so these exist only for the host mapping;
     * cross-checked against {@code REGION_BASE_URLS} in the Python SDK's
     * {@code _constants.py}.
     */
    public static final List<String> REGION_BASE_URLS = immutable(
            "https://api-us-central1.quillrouter.com/v1",
            "https://api-us-east4.quillrouter.com/v1",
            "https://api-europe-west4.quillrouter.com/v1");

    private static final List<String> REGION_HOST_ENUMS = immutable(
            "us_central1", "us_east4", "europe_west4");

    /** Anchored value grammar for {@code x-tr-client} (&sect;3.2). */
    static final Pattern HEADER_VALUE = Pattern.compile("^[a-z0-9_]{1,24}$");

    /** Whole-header byte cap for {@code x-tr-client} (&sect;3.2). */
    static final int MAX_HEADER_BYTES = 160;

    /**
     * Maps a URL to the closed host vocabulary (&sect;5.2). Scheme and host
     * must both match the constant, mirroring the Python SDK's
     * {@code host_enum}; anything unparseable or unrecognised is
     * {@code custom} and never appears on the wire.
     */
    public static String hostEnum(String baseUrl) {
        HttpUrl url = parse(baseUrl);
        if (url == null) {
            return "custom";
        }
        if (schemeHostMatches(url, TrustedRouter.DEFAULT_API_BASE_URL)) {
            return "apex";
        }
        if (schemeHostMatches(url, TrustedRouter.ALIAS_API_BASE_URLS.get(0))) {
            return "ally";
        }
        if (schemeHostMatches(url, TrustedRouter.ALIAS_API_BASE_URLS.get(1))) {
            return "uptime";
        }
        for (int index = 0; index < REGION_BASE_URLS.size(); index++) {
            if (schemeHostMatches(url, REGION_BASE_URLS.get(index))) {
                return REGION_HOST_ENUMS.get(index);
            }
        }
        if (schemeHostMatches(url, TrustedRouter.DEFAULT_CONTROL_BASE_URL)
                || isControlHost(baseUrl)) {
            return "control";
        }
        return "custom";
    }

    /**
     * Whether a URL is the TrustedRouter control plane: https and
     * {@code trustedrouter.com} or a subdomain of it. Mirrors the Python
     * SDK's {@code _control_host}.
     */
    public static boolean isControlHost(String url) {
        HttpUrl parsed = parse(url);
        if (parsed == null) {
            return false;
        }
        String host = parsed.host().toLowerCase(Locale.ROOT);
        return "https".equals(parsed.scheme())
                && ("trustedrouter.com".equals(host)
                        || host.endsWith(".trustedrouter.com"));
    }

    /**
     * Production entry point for {@link #resolveEnabled(Boolean, String,
     * String, Map)}: short-circuits an explicit option BEFORE touching the
     * process environment, and treats a denied environment (a legacy
     * {@code SecurityManager} can veto {@code System.getenv()}) as empty
     * rather than ever letting telemetry resolution fail client
     * construction (&sect;2.2).
     */
    public static boolean resolveEnabled(
            Boolean explicit, String baseUrl, String controlBaseUrl) {
        if (explicit != null) {
            return explicit.booleanValue();
        }
        Map<String, String> environ;
        try {
            environ = System.getenv();
        } catch (SecurityException denied) {
            environ = Collections.emptyMap();
        }
        return resolveEnabled(null, baseUrl, controlBaseUrl, environ);
    }

    /**
     * Resolves the telemetry opt-out precedence (&sect;6.3) without reading
     * process state implicitly, mirroring the Python SDK's
     * {@code resolve_telemetry_enabled}: explicit option, then
     * {@code TRUSTEDROUTER_TELEMETRY}, then {@code DO_NOT_TRACK=1}, then
     * default-on only for a known TrustedRouter inference base AND a
     * TrustedRouter https control host.
     *
     * @param explicit the builder's tri-state {@code telemetry} option
     * @param environ an environment lookup, injected so tests never mutate
     *     process env; production passes {@code System.getenv()}
     */
    public static boolean resolveEnabled(
            Boolean explicit,
            String baseUrl,
            String controlBaseUrl,
            Map<String, String> environ) {
        if (explicit != null) {
            return explicit.booleanValue();
        }
        String configured = environ.get("TRUSTEDROUTER_TELEMETRY");
        configured = configured == null
                ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if ("0".equals(configured) || "false".equals(configured)
                || "off".equals(configured) || "no".equals(configured)) {
            return false;
        }
        if ("1".equals(configured) || "true".equals(configured)
                || "on".equals(configured) || "yes".equals(configured)) {
            return true;
        }
        String doNotTrack = environ.get("DO_NOT_TRACK");
        if (doNotTrack != null && "1".equals(doNotTrack.trim())) {
            return false;
        }
        return !"custom".equals(hostEnum(baseUrl)) && isControlHost(controlBaseUrl);
    }

    /**
     * Classifies a transport failure into the closed ErrorClass vocabulary
     * (&sect;5.2), walking the cause chain the way the Python SDK walks
     * {@code __cause__}/{@code __context__}. Must run at the engine's
     * {@code IOException} catch, BEFORE the SDK flattens the failure into an
     * {@code InternalException} message string (&sect;6.1).
     *
     * <p>The mapping mirrors how OkHttp actually throws: DNS failures are
     * {@link UnknownHostException}; TLS failures are {@link SSLException}
     * subtypes; the connect phase raises {@link ConnectException} ("Connection
     * refused") or {@link SocketTimeoutException} whose message contains
     * "connect"; read stalls raise {@link SocketTimeoutException} without it;
     * peer resets are {@link SocketException} with "reset" in the message;
     * malformed responses raise {@link ProtocolException}.
     */
    public static String classifyTransportError(Throwable error) {
        List<Throwable> chain = causeChain(error);
        for (Throwable item : chain) {
            if (item instanceof SocketTimeoutException && messageContains(item, "connect")) {
                return "connect_timeout";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof SocketTimeoutException) {
                return "read_timeout";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof SSLException) {
                return "tls";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof UnknownHostException) {
                return "dns";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof ConnectException && messageContains(item, "refused")) {
                return "connect_refused";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof SocketException && messageContains(item, "reset")) {
                return "reset";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof ConnectException) {
                return "connect_error";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof ProtocolException) {
                return "protocol_error";
            }
        }
        for (Throwable item : chain) {
            if (item instanceof IOException) {
                return "io_error";
            }
        }
        return "unknown";
    }

    /**
     * Whether the failure is a timeout for the attempt-outcome vocabulary,
     * the Java analogue of the Python SDK's {@code httpx.TimeoutException}
     * check. {@link SocketTimeoutException} always is; a bare
     * {@link InterruptedIOException} counts only in OkHttp's call-timeout
     * shape ({@code InterruptedIOException("timeout")} from
     * {@code okio.AsyncTimeout}) — OkHttp itself uses a plain
     * InterruptedIOException for thread interruption, which is an abort,
     * not a timeout.
     */
    public static boolean isTimeout(Throwable error) {
        for (Throwable item : causeChain(error)) {
            if (item instanceof SocketTimeoutException) {
                return true;
            }
            if (item instanceof InterruptedIOException && messageContains(item, "timeout")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clamps a duration into {@code [0, MAX_DURATION_MS]} in pure long
     * arithmetic. Deliberately takes a {@code long}: computing millis as
     * {@code (nanoEnd - nanoStart) / 1_000_000L} never touches a double, so
     * JLS 5.1.3 double-to-long saturation can never manufacture a plausible
     * giant value here.
     */
    public static long clampDurationMs(long millis) {
        if (millis < 0L) {
            return 0L;
        }
        return Math.min(MAX_DURATION_MS, millis);
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<Throwable>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = error;
        while (current != null && chain.size() < 6 && !seen.contains(current)) {
            chain.add(current);
            seen.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private static boolean messageContains(Throwable error, String needle) {
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean schemeHostMatches(HttpUrl url, String constant) {
        HttpUrl expected = parse(constant);
        return expected != null
                && url.scheme().equals(expected.scheme())
                && url.host().equalsIgnoreCase(expected.host());
    }

    private static HttpUrl parse(String url) {
        if (url == null) {
            return null;
        }
        return HttpUrl.parse(url.trim());
    }

    private static List<String> immutable(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
