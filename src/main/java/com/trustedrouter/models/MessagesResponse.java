package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** Anthropic-compatible Messages API response. */
public final class MessagesResponse extends JsonModel {
    private String id;
    private String type;
    private String role;
    private List<JsonObject> content;
    private String model;
    private String stopReason;
    private String stopSequence;
    private Usage usage;
    public String getId() { return id; }
    public String getType() { return type; }
    public String getRole() { return role; }
    public List<JsonObject> getContent() {
        return content == null ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(content);
    }
    public String getModel() { return model; }
    public String getStopReason() { return stopReason; }
    public String getStopSequence() { return stopSequence; }
    public Usage getUsage() { return usage; }
    public static final class Usage {
        private int inputTokens;
        private int outputTokens;
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
    }
}
