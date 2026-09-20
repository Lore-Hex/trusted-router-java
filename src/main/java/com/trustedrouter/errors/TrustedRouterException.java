package com.trustedrouter.errors;

import com.google.gson.JsonElement;
import java.io.IOException;

/** Base checked exception for API, gateway, provider, and transport failures. */
public class TrustedRouterException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Serialized status code. */
    private final int statusCode;
    /** Serialized payload. */
    private final JsonElement payload;
    /** Serialized layer. */
    private final String layer;
    /** Serialized source. */
    private final String source;
    /** Serialized provider. */
    private final String provider;
    /** Serialized request id. */
    private final String requestId;

    /**
     * Creates a TrustedRouterException.
     *
     * @param statusCode the status code
     * @param message the message
     * @param payload the payload
     */
    public TrustedRouterException(int statusCode, String message, JsonElement payload) {
        this(statusCode, message, payload, null);
    }

    /**
     * Creates a TrustedRouterException.
     *
     * @param statusCode the status code
     * @param message the message
     * @param payload the payload
     * @param cause the cause
     */
    public TrustedRouterException(
            int statusCode, String message, JsonElement payload, Throwable cause) {
        super(message == null || message.isEmpty() ? "TrustedRouter error" : message, cause);
        this.statusCode = statusCode;
        this.payload = payload;
        this.layer = nestedString(payload, "layer");
        this.source = nestedString(payload, "source");
        this.provider = nestedString(payload, "provider");
        this.requestId = nestedString(payload, "request_id");
    }

    /**
     * Returns status code.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns payload.
     *
     * @return the payload
     */
    public JsonElement getPayload() {
        return payload == null ? null : payload.deepCopy();
    }

    /**
     * Routing layer supplied by TrustedRouter's actionable error envelope, when present.
     *
     * @return the layer
     */
    public String getLayer() {
        return layer;
    }

    /**
     * Error source supplied by the gateway or provider adapter, when present.
     *
     * @return the source
     */
    public String getSource() {
        return source;
    }

    /**
     * Attempted provider supplied by the gateway, when present.
     *
     * @return the provider
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Request identifier used to correlate metadata-only logs.
     *
     * @return the request id
     */
    public String getRequestId() {
        return requestId;
    }

    private static String nestedString(JsonElement payload, String key) {
        if (payload == null || !payload.isJsonObject()) {
            return null;
        }
        JsonElement direct = payload.getAsJsonObject().get(key);
        JsonElement error = payload.getAsJsonObject().get("error");
        if ((direct == null || direct.isJsonNull()) && error != null && error.isJsonObject()) {
            direct = error.getAsJsonObject().get(key);
        }
        return com.trustedrouter.internal.WireShape.isString(direct) ? direct.getAsString() : null;
    }
}
