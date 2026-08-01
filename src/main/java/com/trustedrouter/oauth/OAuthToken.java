package com.trustedrouter.oauth;

import com.google.gson.JsonObject;
import com.trustedrouter.models.JsonModel;

/** Delegated TrustedRouter key minted by the OAuth exchange. */
public final class OAuthToken extends JsonModel {
    private String key;
    private String userId;
    private JsonObject identity;
    public String getKey() { return key; }
    public String getUserId() { return userId; }
    public JsonObject getIdentity() { return identity; }
}
