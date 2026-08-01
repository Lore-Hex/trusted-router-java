package com.trustedrouter.models;

import com.google.gson.JsonObject;

/** Public signed release metadata used to pin the attested gateway build. */
public final class TrustRelease extends JsonModel {
    private String imageDigest;
    private String imageReference;
    private String sourceCommit;
    private JsonObject tls;
    private JsonObject dataPolicy;
    public String getImageDigest() { return imageDigest; }
    public String getImageReference() { return imageReference; }
    public String getSourceCommit() { return sourceCommit; }
    public JsonObject getTls() { return tls; }
    public JsonObject getDataPolicy() { return dataPolicy; }
}
