package com.trustedrouter.models;

import com.google.gson.JsonElement;

/** Workspace credit balance envelope; money remains exact strings or integer microdollars. */
public final class CreditsBalance extends JsonModel {
    private JsonElement data;
    public JsonElement getData() { return data; }
}
