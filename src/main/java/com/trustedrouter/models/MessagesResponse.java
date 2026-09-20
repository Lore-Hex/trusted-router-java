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
    /**
     * Returns id.
     *
     * @return the id
     */
    public String getId() { return id; }
    /**
     * Returns type.
     *
     * @return the type
     */
    public String getType() { return type; }
    /**
     * Returns role.
     *
     * @return the role
     */
    public String getRole() { return role; }
    /**
     * Returns content.
     *
     * @return the content
     */
    public List<JsonObject> getContent() {
        return content == null ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(content);
    }
    /**
     * Returns model.
     *
     * @return the model
     */
    public String getModel() { return model; }
    /**
     * Returns stop reason.
     *
     * @return the stop reason
     */
    public String getStopReason() { return stopReason; }
    /**
     * Returns stop sequence.
     *
     * @return the stop sequence
     */
    public String getStopSequence() { return stopSequence; }
    /**
     * Returns usage.
     *
     * @return the usage
     */
    public Usage getUsage() { return usage; }
    /**
     * Represents usage.
     */
    public static final class Usage {
        private int inputTokens;
        private int outputTokens;
        /**
         * Returns input tokens.
         *
         * @return the input tokens
         */
        public int getInputTokens() { return inputTokens; }
        /**
         * Returns output tokens.
         *
         * @return the output tokens
         */
        public int getOutputTokens() { return outputTokens; }
    }
}
