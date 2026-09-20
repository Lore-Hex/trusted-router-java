package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InvalidResponseException;

/** Checks consumed wire fields without coercing their primitive types. */
public final class WireShape {
    private WireShape() {}

    public static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    public static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new InvalidResponseException("Expected a JSON object");
        }
        return value.getAsJsonObject();
    }

    public static String string(JsonElement value) {
        if (!isString(value)) {
            throw new InvalidResponseException("Expected a JSON string");
        }
        return value.getAsString();
    }

    public static void optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value != null && !value.isJsonNull()) { string(value); }
    }
}
