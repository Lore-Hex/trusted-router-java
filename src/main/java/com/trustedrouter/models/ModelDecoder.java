package com.trustedrouter.models;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.trustedrouter.errors.InvalidResponseException;
import com.trustedrouter.internal.JsonSupport;
import com.trustedrouter.internal.WireShape;
import com.trustedrouter.oauth.OAuthToken;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/** Internal/public utility for decoding extensible TrustedRouter responses. */
public final class ModelDecoder {
    private ModelDecoder() {}

    /**
     * Decodes consumed fields strictly and retains all metadata in {@link JsonModel#getRaw()}.
     * @throws InvalidResponseException if the envelope or a consumed field is malformed
     */
    public static <T extends JsonModel> T decode(JsonElement json, Class<T> type) {
        JsonObject object = WireShape.object(json);
        validate(object, type);
        T value = JsonSupport.GSON.fromJson(project(object, type), type);
        value.setRaw(object);
        return value;
    }

    private static void validate(JsonObject object, Class<?> type) {
        if (type == OAuthToken.class) {
            WireShape.string(object.get("key"));
            optionalIdentity(object.get("identity"));
            JsonElement data = object.get("data");
            if (data != null && !data.isJsonNull()) { WireShape.object(data); }
        }
        if (type == UserInfoResponse.class) {
            identity(WireShape.object(object.get("data")));
        }
        if (type == TrustRelease.class) {
            WireShape.optionalString(object, "image_digest");
            WireShape.optionalString(object, "image_reference");
            for (String key : List.of("accepted_image_digests", "accepted_image_references")) {
                JsonElement values = object.get(key);
                if (values == null || values.isJsonNull()) { continue; }
                for (JsonElement value : array(values)) { WireShape.string(value); }
            }
        }
        if (type == ChatCompletion.class || type == ChatCompletionChunk.class) {
            JsonElement choices = object.get("choices");
            if (choices != null && !choices.isJsonNull()) {
                for (JsonElement choice : array(choices)) {
                    JsonObject entry = WireShape.object(choice);
                    String field = type == ChatCompletion.class ? "message" : "delta";
                    JsonElement message = entry.get(field);
                    if (message == null || message.isJsonNull()) { continue; }
                    JsonElement content = WireShape.object(message).get("content");
                    if (content == null || content.isJsonNull()) { continue; }
                    // Chat messages can carry structured content; stream deltas are text.
                    if (type == ChatCompletion.class && content.isJsonArray()) { continue; }
                    WireShape.string(content);
                }
            }
        }
        if (type == ModelList.class) {
            JsonElement data = object.get("data");
            if (data != null && !data.isJsonNull()) {
                for (JsonElement entry : array(data)) {
                    WireShape.optionalString(WireShape.object(entry), "id");
                }
            }
        }
    }

    private static JsonArray array(JsonElement value) {
        if (!value.isJsonArray()) { throw new InvalidResponseException("Expected a JSON array"); }
        return value.getAsJsonArray();
    }

    private static void optionalIdentity(JsonElement value) {
        if (value != null && !value.isJsonNull()) { identity(WireShape.object(value)); }
    }

    private static void identity(JsonObject value) {
        WireShape.optionalString(value, "sub");
        WireShape.optionalString(value, "email");
    }

    // Metadata is not a trust boundary. Build only the representable typed view;
    // getRaw() retains wrong-typed and unknown metadata byte-for-value as a JSON tree.
    private static JsonElement project(JsonElement value, Type type) {
        if (value == null || value.isJsonNull()) { return JsonNull.INSTANCE; }
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == List.class && value.isJsonArray()) {
                JsonArray result = new JsonArray();
                for (JsonElement item : value.getAsJsonArray()) {
                    JsonElement projected = project(item, parameterized.getActualTypeArguments()[0]);
                    if (!projected.isJsonNull()) { result.add(projected); }
                }
                return result;
            }
            if (parameterized.getRawType() == Map.class && value.isJsonObject()) {
                JsonObject result = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                    result.add(entry.getKey(), project(entry.getValue(), parameterized.getActualTypeArguments()[1]));
                }
                return result;
            }
            return JsonNull.INSTANCE;
        }
        if (!(type instanceof Class<?> target)) { return JsonNull.INSTANCE; }
        if (JsonElement.class.isAssignableFrom(target)) {
            return target.isInstance(value) ? value : JsonNull.INSTANCE;
        }
        if (target == String.class) { return WireShape.isString(value) ? value : JsonNull.INSTANCE; }
        if (target == boolean.class || target == Boolean.class) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value : JsonNull.INSTANCE;
        }
        if (target.isPrimitive() || Number.class.isAssignableFrom(target)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { return JsonNull.INSTANCE; }
            try {
                if (target == int.class || target == Integer.class) { value.getAsBigDecimal().intValueExact(); }
                if (target == long.class || target == Long.class) { value.getAsBigDecimal().longValueExact(); }
                return value;
            } catch (ArithmeticException | NumberFormatException invalid) {
                return JsonNull.INSTANCE;
            }
        }
        if (!value.isJsonObject()) { return JsonNull.INSTANCE; }
        JsonObject result = new JsonObject();
        for (Field field : target.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) { continue; }
            String name = FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.translateName(field);
            JsonElement projected = project(value.getAsJsonObject().get(name), field.getGenericType());
            if (!projected.isJsonNull()) { result.add(name, projected); }
        }
        return result;
    }
}
