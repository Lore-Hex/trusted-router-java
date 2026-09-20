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
    /**
     * Returns image digest.
     *
     * @return the image digest
     */
    public String getImageDigest() { return imageDigest; }
    /**
     * Returns accepted image digests.
     *
     * @return the accepted image digests
     */
    public List<String> getAcceptedImageDigests() {
        return acceptedImageDigests == null
                ? Collections.<String>emptyList() : acceptedImageDigests;
    }
    /**
     * Returns image reference.
     *
     * @return the image reference
     */
    public String getImageReference() { return imageReference; }
    /**
     * Returns accepted image references.
     *
     * @return the accepted image references
     */
    public List<String> getAcceptedImageReferences() {
        return acceptedImageReferences == null
                ? Collections.<String>emptyList() : acceptedImageReferences;
    }
    /**
     * Returns source commit.
     *
     * @return the source commit
     */
    public String getSourceCommit() { return sourceCommit; }
    /**
     * Returns tls.
     *
     * @return the tls
     */
    public JsonObject getTls() { return tls; }
    /**
     * Returns data policy.
     *
     * @return the data policy
     */
    public JsonObject getDataPolicy() { return dataPolicy; }
}
