package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** Public signed release metadata used to pin the attested gateway build. */
public final class TrustRelease extends JsonModel {
    private String imageDigest;
    private List<String> acceptedImageDigests;
    private String imageReference;
    private List<String> acceptedImageReferences;
    private String sourceCommit;
    private JsonObject tls;
    private JsonObject dataPolicy;
    public String getImageDigest() { return imageDigest; }
    public List<String> getAcceptedImageDigests() {
        return acceptedImageDigests == null
                ? Collections.<String>emptyList() : acceptedImageDigests;
    }
    public String getImageReference() { return imageReference; }
    public List<String> getAcceptedImageReferences() {
        return acceptedImageReferences == null
                ? Collections.<String>emptyList() : acceptedImageReferences;
    }
    public String getSourceCommit() { return sourceCommit; }
    public JsonObject getTls() { return tls; }
    public JsonObject getDataPolicy() { return dataPolicy; }
}
