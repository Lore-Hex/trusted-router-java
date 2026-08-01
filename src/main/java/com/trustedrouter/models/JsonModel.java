package com.trustedrouter.models;

import com.google.gson.JsonObject;

/** Base for typed responses that retain their complete wire representation. */
public abstract class JsonModel {
    private transient JsonObject raw;

    /** Returns a defensive copy of the full response object, including unknown fields. */
    public final JsonObject getRaw() {
        return raw == null ? new JsonObject() : raw.deepCopy();
    }

    final void setRaw(JsonObject value) {
        this.raw = value == null ? null : value.deepCopy();
    }
}
