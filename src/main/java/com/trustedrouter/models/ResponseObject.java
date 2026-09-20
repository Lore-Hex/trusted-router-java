package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** OpenAI Responses API response object. */
public final class ResponseObject extends JsonModel {
    private String id;
    private String object;
    private Long createdAt;
    private String status;
    private String model;
    private List<JsonObject> output;
    private JsonObject usage;
    /**
     * Returns id.
     *
     * @return the id
     */
    public String getId() { return id; }
    /**
     * Returns object.
     *
     * @return the object
     */
    public String getObject() { return object; }
    /**
     * Returns created at.
     *
     * @return the created at
     */
    public Long getCreatedAt() { return createdAt; }
    /**
     * Returns status.
     *
     * @return the status
     */
    public String getStatus() { return status; }
    /**
     * Returns model.
     *
     * @return the model
     */
    public String getModel() { return model; }
    /**
     * Returns output.
     *
     * @return the output
     */
    public List<JsonObject> getOutput() {
        return output == null ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(output);
    }
    /**
     * Returns usage.
     *
     * @return the usage
     */
    public JsonObject getUsage() { return usage; }
}
