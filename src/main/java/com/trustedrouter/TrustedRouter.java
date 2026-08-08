package com.trustedrouter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable SDK constants and orchestration tool helpers. */
public final class TrustedRouter {
    public static final String VERSION = "0.1.0";
    public static final String DEFAULT_API_BASE_URL = "https://api.trustedrouter.com/v1";
    public static final String DEFAULT_CONTROL_BASE_URL = "https://trustedrouter.com/v1";

    /**
     * Exact aliases of {@link #DEFAULT_API_BASE_URL}, on separate domains served
     * by separate DNS providers (trustedrouter.com from Google Cloud DNS, these
     * two from Route 53).
     *
     * <p>The domain is a single point of failure sitting above the whole
     * deployment: a zone that stops answering, a registrar lock, or a resolver
     * handing out a stale record takes the API down no matter how many clouds
     * are behind it. These names resolve to the same attested enclaves, so
     * falling back to one costs nothing and is invisible to callers.
     */
    public static final List<String> ALIAS_API_BASE_URLS = immutableList(
            "https://api.allyrouter.com/v1",
            "https://api.uptimerouter.com/v1");
    public static final String DEFAULT_TRUST_RELEASE_URL =
            "https://trust.trustedrouter.com/trust/gcp-release.json";
    public static final String DEFAULT_STATUS_URL =
            "https://status.trustedrouter.com/status.json";
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L;
    public static final long DEFAULT_FUSION_TIMEOUT_MILLIS = 600_000L;

    public static final String AUTO_MODEL = "trustedrouter/auto";
    public static final String FAST_MODEL = "trustedrouter/fast";
    public static final String ZDR_MODEL = "trustedrouter/zdr";
    public static final String E2E_MODEL = "trustedrouter/e2e";
    public static final String CONFIDENTIAL_MODEL = "trustedrouter/confidential";
    public static final String EU_MODEL = "trustedrouter/eu";
    public static final String US_MODEL = "trustedrouter/us";
    public static final String FUSION_MODEL = "trustedrouter/fusion";
    public static final String SYNTH_MODEL = "trustedrouter/synth";
    public static final String ADVISOR_MODEL = "trustedrouter/advisor";
    public static final String SELECTOR_MODEL = "trustedrouter/selector";
    public static final String MAP_REDUCE_MODEL = "trustedrouter/mapreduce";
    public static final String SUBAGENT_MODEL = "trustedrouter/subagent";
    public static final String SOCRATES_MODEL = "trustedrouter/socrates-1.1";
    public static final String PROMETHEUS_MODEL = "trustedrouter/prometheus-2.0";
    public static final String ZEUS_MODEL = "trustedrouter/zeus-1.0";
    public static final String ATHENA_MODEL = "trustedrouter/athena";

    public static final List<String> FUSION_FREEDOM_PANEL = immutableList(
            "minimax/minimax-m3",
            "~kimi/latest",
            "~zai/glm-latest",
            "google/gemma-4-31b-it",
            "deepseek/deepseek-v4-flash");

    public static final List<String> FUSION_FREEDOM_FALLBACK_JUDGES = immutableList(
            "minimax/minimax-m3",
            "~zai/glm-latest",
            "~kimi/latest",
            "deepseek/deepseek-v4-flash",
            "google/gemma-4-31b-it");

    public static final List<String> FUSION_FREEDOM_FALLBACK_FINALS =
            FUSION_FREEDOM_FALLBACK_JUDGES;

    public static final String SELECTION_SYNTHESIZE = "synthesize";
    public static final String SELECTION_SYNTHESIZE_NON_REFUSALS = "synthesize_non_refusals";
    public static final String SELECTION_FIRST_SUCCESS = "first_success";
    public static final String SELECTION_FIRST_NON_REFUSAL = "first_non_refusal";

    private TrustedRouter() {}

    /** Builds a {@code trustedrouter:fusion} tool from gateway-native parameters. */
    public static JsonObject fusionTool(JsonObject parameters) {
        return orchestrationTool("trustedrouter:fusion", parameters);
    }

    /** Builds a {@code trustedrouter:advisor} tool from gateway-native parameters. */
    public static JsonObject advisorTool(JsonObject parameters) {
        return orchestrationTool("trustedrouter:advisor", parameters);
    }

    /** Builds a {@code trustedrouter:selector} tool from gateway-native parameters. */
    public static JsonObject selectorTool(JsonObject parameters) {
        return orchestrationTool("trustedrouter:selector", parameters);
    }

    /** Builds a {@code trustedrouter:mapreduce} tool from gateway-native parameters. */
    public static JsonObject mapReduceTool(JsonObject parameters) {
        return orchestrationTool("trustedrouter:mapreduce", parameters);
    }

    /** Builds a {@code trustedrouter:subagent} tool from gateway-native parameters. */
    public static JsonObject subagentTool(JsonObject parameters) {
        return orchestrationTool("trustedrouter:subagent", parameters);
    }

    /** Builds a TrustedRouter orchestration tool without changing its parameters. */
    public static JsonObject orchestrationTool(String type, JsonObject parameters) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", type);
        tool.add("parameters", parameters == null ? new JsonObject() : parameters.deepCopy());
        return tool;
    }

    /** Converts a list of strings to a JSON array. */
    public static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    /** Converts a Java duration to a positive millisecond timeout. */
    public static long timeoutMillis(Duration duration) {
        if (duration == null) {
            throw new NullPointerException("duration");
        }
        long millis = duration.toMillis();
        if (millis < 0) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        return millis;
    }

    private static List<String> immutableList(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
