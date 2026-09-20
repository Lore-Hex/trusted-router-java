package com.trustedrouter.oauth;

import com.google.gson.JsonObject;
import com.trustedrouter.models.JsonModel;

/** Delegated TrustedRouter key minted by the OAuth exchange. */
public final class OAuthToken extends JsonModel {
    private String key;
    private String userId;
    private JsonObject identity;
    /**
     * Returns key.
     *
     * @return the key
     */
    public String getKey() { return key; }
    /**
     * Returns user id.
     *
     * @return the user id
     */
    public String getUserId() { return userId; }
    /**
     * Returns identity.
     *
     * @return the identity
     */
    public JsonObject getIdentity() { return identity; }
}
