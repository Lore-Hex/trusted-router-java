package com.trustedrouter.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InvalidResponseException;

/** Checks consumed wire fields without coercing their primitive types. */
public final class WireShape {
    private WireShape() {}

    /**
     * Returns string.
     *
     * @param value the string
     * @return the string
     */
    public static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    /**
     * Performs the object operation.
     *
     * @param value the object
     * @return the object
     */
    public static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new InvalidResponseException("Expected a JSON object");
        }
        return value.getAsJsonObject();
    }

    /**
     * Performs the string operation.
     *
     * @param value the string
     * @return the string
     */
    public static String string(JsonElement value) {
        if (!isString(value)) {
            throw new InvalidResponseException("Expected a JSON string");
        }
        return value.getAsString();
    }

    /**
     * Performs the optional string operation.
     *
     * @param object the object
     * @param name the name
     */
    public static void optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value != null && !value.isJsonNull()) { string(value); }
    }
}
