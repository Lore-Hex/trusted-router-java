package com.trustedrouter.models;

import com.google.gson.JsonElement;
import com.trustedrouter.internal.JsonSupport;

/** Internal/public utility for decoding extensible TrustedRouter responses. */
public final class ModelDecoder {
    private ModelDecoder() {}

    public static <T extends JsonModel> T decode(JsonElement json, Class<T> type) {
        T value = JsonSupport.GSON.fromJson(json, type);
        if (json != null && json.isJsonObject()) {
            value.setRaw(json.getAsJsonObject());
        }
        return value;
    }
}
