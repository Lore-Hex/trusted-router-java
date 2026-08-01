package com.trustedrouter.errors;

import com.google.gson.JsonElement;
import java.io.IOException;

/** Base checked exception for API, gateway, provider, and transport failures. */
public class TrustedRouterException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final JsonElement payload;
    private final String layer;
    private final String source;

    public TrustedRouterException(int statusCode, String message, JsonElement payload) {
        this(statusCode, message, payload, null);
    }

    public TrustedRouterException(
            int statusCode, String message, JsonElement payload, Throwable cause) {
        super(message == null || message.isEmpty() ? "TrustedRouter error" : message, cause);
        this.statusCode = statusCode;
        this.payload = payload;
        this.layer = nestedString(payload, "layer");
        this.source = nestedString(payload, "source");
    }

    public int getStatusCode() {
        return statusCode;
    }

    public JsonElement getPayload() {
        return payload == null ? null : payload.deepCopy();
    }

    /** Routing layer supplied by TrustedRouter's actionable error envelope, when present. */
    public String getLayer() {
        return layer;
    }

    /** Error source supplied by the gateway or provider adapter, when present. */
    public String getSource() {
        return source;
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
        return direct != null && direct.isJsonPrimitive() ? direct.getAsString() : null;
    }
}
