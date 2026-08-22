package com.trustedrouter.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.TrustedRouter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
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
 * <p>This SDK implements both channels of the contract: the HEADER channel
 * (&sect;3.2 {@code x-tr-client}), where per-attempt facts ride the request
 * the caller already made, and the BEACON channel (&sect;4/&sect;5), where
 * {@link TelemetryReporter} posts bounded, content-free batches of sampled
 * events and exact per-minute counters to the control plane from its own
 * single-shot HTTP client (owner decision 2026-08-21: beacons ship in all
 * SDKs now, superseding the &sect;9 step 7 / &sect;10 "Python first"
 * ordering). The vocabulary, bounds, and helpers here mirror the Python
 * reference ({@code _constants.py}, {@code _telemetry.py}) byte for byte.
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

    /** Final-outcome vocabulary (&sect;5.2): every attempt outcome plus {@code exhausted}. */
    public static final List<String> FINAL_OUTCOMES = immutable(
            "ok", "http_error", "transport_error", "timeout", "stream_broken",
            "aborted", "exhausted");

    /** Timeout-phase vocabulary (&sect;5.2). */
    public static final List<String> TIMEOUT_PHASES = immutable(
            "none", "connect", "first_byte", "idle", "total");

    /** HTTP status-class vocabulary (&sect;5.2). */
    public static final List<String> HTTP_STATUS_CLASSES = immutable(
            "none", "2xx", "4xx", "429", "5xx");

    /** Latency histogram buckets (&sect;5.2), upper-bound exclusive. */
    public static final List<String> LATENCY_BUCKETS = immutable(
            "lt100", "lt200", "lt400", "lt800", "lt1600", "lt3200", "lt6400",
            "lt12800", "lt25600", "lt51200", "lt102400", "ge102400");

    /** Error-source vocabulary (&sect;5.3). */
    public static final List<String> ERROR_SOURCES = immutable("router", "provider", "unknown");

    /** Sample-reason vocabulary (&sect;5.3). */
    public static final List<String> SAMPLE_REASONS = immutable(
            "failure", "retried", "slow", "random");

    /** Counter levels (&sect;5.4). */
    public static final List<String> LEVELS = immutable("attempt", "request");

    /**
     * Methods the beacon schema accepts. &sect;5.3 lists PUT/PATCH/DELETE
     * too, but the executable schema module allows only GET and POST
     * (module-wins ruling): other methods produce no event and no counters.
     */
    public static final List<String> BEACON_METHODS = immutable("GET", "POST");

    private static final long[] LATENCY_UPPER_BOUNDS = {
        100L, 200L, 400L, 800L, 1_600L, 3_200L, 6_400L, 12_800L, 25_600L, 51_200L, 102_400L,
    };

    /** Reporter bounds (&sect;6.2), mirroring the Python SDK's {@code _constants.py}. */
    public static final long FLUSH_INTERVAL_MS = 30_000L;
    public static final int MAX_EVENTS = 1_000;
    public static final int MAX_BATCH_EVENTS = 100;
    public static final int MAX_BATCH_COUNTERS = 200;
    public static final int MAX_WINDOW_KEYS = 256;
    public static final long RETENTION_MS = 86_400_000L;
    public static final long RETENTION_BYTES = 524_288L;
    public static final long BACKOFF_MIN_MS = 60_000L;
    public static final long BACKOFF_MAX_MS = 600_000L;
    /** Longest {@code Retry-After} the beacon honours, in milliseconds (&sect;6.2). */
    public static final long MAX_RETRY_AFTER_MS = 600_000L;
    /** Longest {@code pause_seconds} a 202 policy may impose, in milliseconds (&sect;4). */
    public static final long MAX_PAUSE_MS = 86_400_000L;
    /** Buffered bytes that trigger an early flush (&sect;6.2: 60 KB). */
    public static final int BATCH_TRIGGER_BYTES = 60 * 1024;
    /** Buffered events that trigger an early flush (&sect;6.2). */
    public static final int BATCH_TRIGGER_EVENTS = 50;
    /** Hard cap on one serialised batch (&sect;4: 413 above). */
    public static final int MAX_BATCH_BYTES = 65_536;
    /** Hard cap on every age on the wire, in milliseconds (&sect;5.3/&sect;5.4). */
    public static final long MAX_AGE_MS = 86_400_000L;
    /** Hard cap on every count on the wire (&sect;5.4). */
    public static final long MAX_COUNT = 10_000_000L;
    /** Default random sampling rate for healthy first-attempt successes (&sect;5.3). */
    public static final double DEFAULT_SUCCESS_SAMPLE_RATE = 0.01d;
    /** Successes slower than this are always sampled (&sect;5.3). */
    public static final long SLOW_REQUEST_MS = 30_000L;
    /** Bound on the process-exit flush (&sect;6.2). */
    public static final long FINAL_FLUSH_MS = 2_000L;
    /** Beacon sender per-call timeout (mirrors the Python reference's 5 s client). */
    public static final long SENDER_TIMEOUT_MS = 5_000L;

    /** Anchored model grammar (&sect;5.3); anything else is sent as null. */
    static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:/~@-]{1,128}$");

    /** Anchored enclave request-id grammar (&sect;3.3). */
    static final Pattern REQUEST_ID = Pattern.compile("^rlog_[0-9a-f]{32}$");

    /** SemVer 2.0 grammar for the SDK identity version (&sect;5.1). */
    static final Pattern SEMVER = Pattern.compile(
            "^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    /** Runtime token grammar for the SDK identity (&sect;5.1). */
    static final Pattern RUNTIME = Pattern.compile("^[a-z]{1,10}/[0-9A-Za-z.+-]{1,24}$");

    /**
     * Compact JSON for the beacon wire: explicit nulls, because the schema's
     * nullable fields are present-but-null in the Python reference, and no
     * HTML escaping, so byte counts match {@code json.dumps(separators=(",", ":"))}.
     */
    static final Gson WIRE_JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

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
     *
     * <p>DOCUMENTED APPROXIMATION: OkHttp surfaces a stalled request-body
     * upload as the same generic {@code SocketTimeoutException("timeout")}
     * as a read stall, so a write stall classifies as {@code read_timeout}
     * rather than {@code write_timeout} (the outcome is {@code timeout}
     * either way). Distinguishing them would need a per-call
     * {@code EventListener} chained onto the caller's own OkHttp client —
     * request-path machinery this header-only phase deliberately avoids
     * (&sect;2.2). Pinned by
     * {@code ClientTelemetryHeaderTest.aStalledUploadClassifiesAsTimeout}.
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
            // Every remaining PROVEN connect-phase failure is connect_error,
            // never the io_error catch-all: httpx raises one ConnectError for
            // all of these, so the Python reference classifies them
            // connect_error and Java must agree. ConnectException covers a
            // generic connect failure; NoRouteToHostException (EHOSTUNREACH)
            // and BindException (a local bind/EADDRINUSE before any packet)
            // are siblings of it, not subtypes, so an instanceof
            // ConnectException test alone misses them. Refusal stays above
            // this: connect_refused needs proven syscall-level refusal.
            //
            // Deliberately TYPES ONLY, no message matching. A bare
            // SocketException("Network is unreachable") can also be thrown
            // AFTER the connection is established, where the phase is not
            // proven and the Python oracle's analogous httpx.ReadError maps to
            // io_error; classifying on the substring would overclaim the
            // connect phase. PortUnreachableException is likewise excluded —
            // Java defines it for an ICMP reply on a connected datagram, not
            // for TCP connection setup.
            if (item instanceof ConnectException
                    || item instanceof NoRouteToHostException
                    || item instanceof BindException) {
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
     * Whether a whole {@code x-tr-client} value implements the exact v1
     * grammar (&sect;3.2), including the closed key set and order, conditional
     * retry fields, per-key vocabularies and numeric ranges, ASCII-only wire
     * representation, and the 160-byte UTF-8 cap.
     *
     * <p>The recorder already validates what it produces; this is the wire's
     * independent check, so a value that reached the enforcer by any other
     * route cannot add a field, repeat a key, smuggle free text, or claim an
     * out-of-range semantic value.
     */
    public static boolean isWellFormedHeader(String header) {
        if (header == null || header.isEmpty() || header.length() > MAX_HEADER_BYTES) {
            return false;
        }
        if (header.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_BYTES) {
            return false;
        }
        for (int index = 0; index < header.length(); index++) {
            if (header.charAt(index) > 0x7f) {
                return false;
            }
        }

        String[] pairs = header.split(";", -1);
        String version = pairValue(pairs, 0, "v");
        String attemptValue = pairValue(pairs, 1, "a");
        if (!"1".equals(version) || !isBoundedInteger(attemptValue, 0L, 99L)) {
            return false;
        }
        if (isZeroInteger(attemptValue)) {
            return pairs.length == 3 && isBit(pairValue(pairs, 2, "s"));
        }
        return pairs.length == 9
                && isPreviousOutcome(pairValue(pairs, 2, "po"))
                && isPreviousErrorClass(pairValue(pairs, 3, "pc"))
                && isPreviousHost(pairValue(pairs, 4, "ph"))
                && isBoundedInteger(pairValue(pairs, 5, "pm"), 0L, MAX_DURATION_MS)
                && isBoundedInteger(pairValue(pairs, 6, "sm"), 0L, MAX_DURATION_MS)
                && isBit(pairValue(pairs, 7, "s"))
                && isBit(pairValue(pairs, 8, "fo"));
    }

    private static String pairValue(String[] pairs, int index, String key) {
        if (index >= pairs.length) {
            return null;
        }
        String prefix = key + "=";
        String pair = pairs[index];
        if (!pair.startsWith(prefix)) {
            return null;
        }
        String value = pair.substring(prefix.length());
        return HEADER_VALUE.matcher(value).matches() ? value : null;
    }

    private static boolean isBoundedInteger(String value, long minimum, long maximum) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        long parsed = 0L;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') {
                return false;
            }
            parsed = parsed * 10L + (c - '0');
            if (parsed > maximum) {
                return false;
            }
        }
        return parsed >= minimum;
    }

    private static boolean isBit(String value) {
        return "0".equals(value) || "1".equals(value);
    }

    private static boolean isZeroInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPreviousOutcome(String value) {
        return "none".equals(value)
                || "http_error".equals(value)
                || "transport_error".equals(value)
                || "timeout".equals(value)
                || "stream_broken".equals(value);
    }

    private static boolean isPreviousErrorClass(String value) {
        return "none".equals(value) || ERROR_CLASSES.contains(value);
    }

    private static boolean isPreviousHost(String value) {
        return "none".equals(value) || HOSTS.contains(value);
    }

    /**
     * Maps an inference path to the closed Endpoint vocabulary (&sect;5.2),
     * mirroring the Python SDK's {@code endpoint_enum}: the query string is
     * ignored, trailing slashes are trimmed, the four exact paths map
     * directly, the four prefixed families match themselves or a sub-path,
     * and everything else is {@code inference_other}. A missing leading
     * slash is normalised first because {@code CandidateUrls.joinUrl}
     * accepts either spelling for the same endpoint.
     */
    public static String endpointEnum(String path) {
        String value = path == null ? "" : path.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        if ("/chat/completions".equals(value)) {
            return "chat_completions";
        }
        if ("/messages".equals(value)) {
            return "messages";
        }
        if ("/responses".equals(value)) {
            return "responses";
        }
        if ("/embeddings".equals(value)) {
            return "embeddings";
        }
        String[][] prefixed = {
            {"/images", "images"}, {"/videos", "videos"}, {"/models", "models"},
            {"/fusion", "fusion"},
        };
        for (String[] entry : prefixed) {
            if (value.equals(entry[0]) || value.startsWith(entry[0] + "/")) {
                return entry[1];
            }
        }
        return "inference_other";
    }

    /** The upper-bound-exclusive latency bucket for a duration (&sect;5.2). */
    public static String latencyBucket(long millis) {
        long value = Math.max(0L, millis);
        for (int index = 0; index < LATENCY_UPPER_BOUNDS.length; index++) {
            if (value < LATENCY_UPPER_BOUNDS[index]) {
                return LATENCY_BUCKETS.get(index);
            }
        }
        return LATENCY_BUCKETS.get(LATENCY_BUCKETS.size() - 1);
    }

    /** The HTTP status class for a counter key (&sect;5.2); null status is {@code none}. */
    public static String statusClass(Integer status) {
        if (status == null) {
            return "none";
        }
        int value = status.intValue();
        if (value >= 200 && value <= 299) {
            return "2xx";
        }
        if (value == 429) {
            return "429";
        }
        if (value >= 400 && value <= 499) {
            return "4xx";
        }
        if (value >= 500 && value <= 599) {
            return "5xx";
        }
        return "none";
    }

    /**
     * Whether the configured timeout for a phase meets the contract floor
     * (&sect;5.4: connect 10 s, first byte 60 s, idle 30 s); other phases
     * and an unconfigured timeout never do.
     */
    public static boolean timeoutFloorMet(String phase, Long configuredMs) {
        if (configuredMs == null || phase == null) {
            return false;
        }
        long floor;
        if ("connect".equals(phase)) {
            floor = 10_000L;
        } else if ("first_byte".equals(phase)) {
            floor = 60_000L;
        } else if ("idle".equals(phase)) {
            floor = 30_000L;
        } else {
            return false;
        }
        return configuredMs.longValue() >= floor;
    }

    /**
     * The timeout phase a transport failure proves (&sect;5.2), the Java
     * analogue of the phase half of the Python SDK's
     * {@code classify_transport_error}: a connect timeout is {@code connect},
     * a read (or write, which OkHttp cannot tell apart — see
     * {@link #classifyTransportError}) stall is {@code first_byte}, and
     * OkHttp's whole-call timeout — which httpx has no equivalent for — is
     * {@code total}. Everything else is {@code none}. A stall after the
     * first body byte is re-phased to {@code idle} by the recorder.
     */
    public static String timeoutPhase(Throwable error) {
        if (!isTimeout(error)) {
            return "none";
        }
        String errorClass = classifyTransportError(error);
        if ("connect_timeout".equals(errorClass)) {
            return "connect";
        }
        if ("read_timeout".equals(errorClass) || "write_timeout".equals(errorClass)) {
            return "first_byte";
        }
        return "total";
    }

    /**
     * The bounded SDK identity included in every batch (&sect;5.1), built
     * from the process properties and mirroring the Python SDK's
     * {@code sdk_identity} fallbacks: an out-of-grammar version becomes
     * {@code 0.0.0}, an out-of-grammar runtime token {@code java/0}. Never
     * throws, even under a {@code SecurityManager} that denies properties.
     */
    public static JsonObject sdkIdentity() {
        return sdkIdentity(
                TrustedRouter.VERSION,
                property("java.version"),
                property("os.name"),
                property("java.vm.name") + " " + property("java.runtime.name") + " "
                        + property("java.vendor"),
                property("os.arch"));
    }

    /** Pure form of {@link #sdkIdentity()} for tests. */
    static JsonObject sdkIdentity(
            String version,
            String javaVersion,
            String osName,
            String runtimeDescription,
            String osArch) {
        String sdkVersion = version == null ? "" : version;
        if (sdkVersion.length() > 32 || !SEMVER.matcher(sdkVersion).matches()) {
            sdkVersion = "0.0.0";
        }
        String runtime = "java/" + RequestFactory.runtimeToken(javaVersion);
        if (!RUNTIME.matcher(runtime).matches()) {
            runtime = "java/0";
        }
        JsonObject identity = new JsonObject();
        identity.addProperty("name", "tr-java");
        identity.addProperty("version", sdkVersion);
        identity.addProperty("lang", "java");
        identity.addProperty("runtime", runtime);
        identity.addProperty("os", osEnum(osName, runtimeDescription));
        identity.addProperty("arch", archEnum(osArch));
        return identity;
    }

    /**
     * Maps {@code os.name} (and the VM/runtime description, which is how
     * Android identifies itself) to the closed OS vocabulary (&sect;5.1).
     */
    public static String osEnum(String osName, String runtimeDescription) {
        String runtime = runtimeDescription == null
                ? "" : runtimeDescription.toLowerCase(Locale.ROOT);
        if (runtime.contains("android")) {
            return "android";
        }
        String name = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return "macos";
        }
        if (name.startsWith("linux")) {
            return "linux";
        }
        if (name.startsWith("windows")) {
            return "windows";
        }
        if (name.startsWith("freebsd")) {
            return "freebsd";
        }
        return "other";
    }

    /** Maps {@code os.arch} to the closed architecture vocabulary (&sect;5.1). */
    public static String archEnum(String osArch) {
        String value = osArch == null ? "" : osArch.trim().toLowerCase(Locale.ROOT);
        if ("x86_64".equals(value) || "amd64".equals(value)) {
            return "x64";
        }
        if ("x86".equals(value) || "i386".equals(value) || "i486".equals(value)
                || "i586".equals(value) || "i686".equals(value)) {
            return "x32";
        }
        if ("aarch64".equals(value) || "arm64".equals(value)) {
            return "arm64";
        }
        if (value.startsWith("arm")) {
            return "arm";
        }
        if (value.startsWith("wasm")) {
            return "wasm";
        }
        return "other";
    }

    /**
     * Re-validates a caller-supplied identity field by field against the
     * vocabulary, falling back to {@link #sdkIdentity()} per field, exactly
     * like the Python SDK's {@code _normalise_sdk_identity}.
     */
    public static JsonObject normaliseSdkIdentity(JsonObject identity) {
        JsonObject fallback = sdkIdentity();
        JsonObject source = identity == null ? new JsonObject() : identity;
        JsonObject result = new JsonObject();
        String name = string(source, "name");
        result.addProperty("name", isOneOf(name, "tr-py", "tr-js", "tr-go", "tr-rust",
                "tr-java", "tr-swift") ? name : fallback.get("name").getAsString());
        String version = string(source, "version");
        result.addProperty("version", version != null && version.length() <= 32
                && SEMVER.matcher(version).matches()
                ? version : fallback.get("version").getAsString());
        String lang = string(source, "lang");
        result.addProperty("lang", isOneOf(lang, "python", "js", "go", "rust", "java", "swift")
                ? lang : fallback.get("lang").getAsString());
        String runtime = string(source, "runtime");
        result.addProperty("runtime", runtime != null && RUNTIME.matcher(runtime).matches()
                ? runtime : fallback.get("runtime").getAsString());
        String os = string(source, "os");
        result.addProperty("os", isOneOf(os, "linux", "macos", "windows", "ios", "android",
                "freebsd", "other") ? os : fallback.get("os").getAsString());
        String arch = string(source, "arch");
        result.addProperty("arch", isOneOf(arch, "x64", "x32", "arm", "arm64", "wasm", "other")
                ? arch : fallback.get("arch").getAsString());
        return result;
    }

    /** Clamps a count or duration into {@code [minimum, maximum]}. */
    public static long bounded(long value, long minimum, long maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    /** A value inside {@code [minimum, maximum]}, or null when absent or outside. */
    public static Long boundedOrNull(Long value, long minimum, long maximum) {
        if (value == null || value.longValue() < minimum || value.longValue() > maximum) {
            return null;
        }
        return value;
    }

    /** Parses a finite double from a JSON number or numeric string, else null. */
    static Double finiteDouble(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            double parsed = value.getAsJsonPrimitive().isNumber()
                    ? value.getAsDouble() : Double.parseDouble(value.getAsString().trim());
            return Double.isNaN(parsed) || Double.isInfinite(parsed)
                    ? null : Double.valueOf(parsed);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static boolean isOneOf(String value, String... allowed) {
        return value != null && Arrays.asList(allowed).contains(value);
    }

    private static String property(String name) {
        try {
            String value = System.getProperty(name);
            return value == null ? "" : value;
        } catch (SecurityException denied) {
            return "";
        }
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

    static List<Throwable> causeChain(Throwable error) {
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
