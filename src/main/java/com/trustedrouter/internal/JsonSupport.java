package com.trustedrouter.internal;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.Reader;

/** Shared strict-enough JSON handling. */
public final class JsonSupport {
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private JsonSupport() {}

    public static JsonElement parse(String value) {
        return JsonParser.parseString(value);
    }

    public static JsonElement parse(Reader value) {
        return JsonParser.parseReader(value);
    }

    public static JsonElement parseOrNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return parse(value);
        } catch (JsonSyntaxException ignored) {
            return null;
        }
    }

    public static String errorMessage(JsonElement payload) {
        if (payload == null || !payload.isJsonObject()) {
            return "TrustedRouter error";
        }
        JsonObject object = payload.getAsJsonObject();
        JsonElement error = object.get("error");
        if (error != null && error.isJsonObject()) {
            String message = nonEmptyString(error.getAsJsonObject().get("message"));
            if (message != null) {
                return message;
            }
            String type = nonEmptyString(error.getAsJsonObject().get("type"));
            return type == null ? "TrustedRouter error" : type;
        }
        String message = nonEmptyString(object.get("message"));
        return message == null ? "TrustedRouter error" : message;
    }

    private static String nonEmptyString(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString();
        return text.isEmpty() ? null : text;
    }
}
