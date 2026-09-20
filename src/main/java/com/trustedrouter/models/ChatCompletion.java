package com.trustedrouter.models;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** OpenAI-compatible non-streaming chat completion. */
public final class ChatCompletion extends JsonModel {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

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
     * Returns created.
     *
     * @return the created
     */
    public long getCreated() { return created; }
    /**
     * Returns model.
     *
     * @return the model
     */
    public String getModel() { return model; }
    /**
     * Returns choices.
     *
     * @return the choices
     */
    public List<Choice> getChoices() {
        return choices == null ? Collections.<Choice>emptyList() : Collections.unmodifiableList(choices);
    }
    /**
     * Returns usage.
     *
     * @return the usage
     */
    public Usage getUsage() { return usage; }

    /**
     * Returns the first choice's text, or an empty string when no message is present.
     *
     * @return the first text value, or an empty string
     */
    public String firstText() {
        if (choices == null || choices.isEmpty() || choices.get(0).message == null) {
            return "";
        }
        return choices.get(0).message.text();
    }

    /**
     * Represents choice.
     */
    public static final class Choice {
        private int index;
        private Message message;
        private String finishReason;
        private JsonObject logprobs;

        /**
         * Returns index.
         *
         * @return the index
         */
        public int getIndex() { return index; }
        /**
         * Returns message.
         *
         * @return the message
         */
        public Message getMessage() { return message; }
        /**
         * Returns finish reason.
         *
         * @return the finish reason
         */
        public String getFinishReason() { return finishReason; }
        /**
         * Returns logprobs.
         *
         * @return the logprobs
         */
        public JsonObject getLogprobs() { return logprobs; }
    }

    /**
     * Represents message.
     */
    public static final class Message {
        private String role;
        private JsonElement content;
        private String name;
        private List<JsonObject> toolCalls;
        private String toolCallId;

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
        public JsonElement getContent() { return content; }
        /**
         * Returns name.
         *
         * @return the name
         */
        public String getName() { return name; }
        /**
         * Returns tool calls.
         *
         * @return the tool calls
         */
        public List<JsonObject> getToolCalls() {
            return toolCalls == null
                    ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(toolCalls);
        }
        /**
         * Returns tool call id.
         *
         * @return the tool call id
         */
        public String getToolCallId() { return toolCallId; }
        /**
         * Returns scalar message content as text, or an empty string for absent or structured content.
         *
         * @return the text
         */
        public String text() {
            return content != null && content.isJsonPrimitive() ? content.getAsString() : "";
        }
    }

    /** Token and billing metadata. Unknown provider fields remain available through raw JSON. */
    public static final class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private Long costMicrodollars;
        private JsonObject providerUsage;

        /**
         * Returns prompt tokens.
         *
         * @return the prompt tokens
         */
        public int getPromptTokens() { return promptTokens; }
        /**
         * Returns completion tokens.
         *
         * @return the completion tokens
         */
        public int getCompletionTokens() { return completionTokens; }
        /**
         * Returns total tokens.
         *
         * @return the total tokens
         */
        public int getTotalTokens() { return totalTokens; }
        /**
         * Returns cost microdollars.
         *
         * @return the cost microdollars
         */
        public Long getCostMicrodollars() { return costMicrodollars; }
        /**
         * Returns provider usage.
         *
         * @return the provider usage
         */
        public JsonObject getProviderUsage() { return providerUsage; }
    }
}
