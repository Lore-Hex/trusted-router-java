package com.trustedrouter.models;

import com.google.gson.JsonElement;

/** Logout result. */
public final class LogoutResponse extends JsonModel {
    private JsonElement data;
    public JsonElement getData() { return data; }
}
