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
    public String getId() { return id; }
    public String getObject() { return object; }
    public Long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getModel() { return model; }
    public List<JsonObject> getOutput() {
        return output == null ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(output);
    }
    public JsonObject getUsage() { return usage; }
}
