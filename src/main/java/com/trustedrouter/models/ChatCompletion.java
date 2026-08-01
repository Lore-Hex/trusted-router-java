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

    public String getId() { return id; }
    public String getObject() { return object; }
    public long getCreated() { return created; }
    public String getModel() { return model; }
    public List<Choice> getChoices() {
        return choices == null ? Collections.<Choice>emptyList() : Collections.unmodifiableList(choices);
    }
    public Usage getUsage() { return usage; }

    public String firstText() {
        if (choices == null || choices.isEmpty() || choices.get(0).message == null) {
            return "";
        }
        return choices.get(0).message.text();
    }

    public static final class Choice {
        private int index;
        private Message message;
        private String finishReason;
        private JsonObject logprobs;

        public int getIndex() { return index; }
        public Message getMessage() { return message; }
        public String getFinishReason() { return finishReason; }
        public JsonObject getLogprobs() { return logprobs; }
    }

    public static final class Message {
        private String role;
        private JsonElement content;
        private String name;
        private List<JsonObject> toolCalls;
        private String toolCallId;

        public String getRole() { return role; }
        public JsonElement getContent() { return content; }
        public String getName() { return name; }
        public List<JsonObject> getToolCalls() {
            return toolCalls == null
                    ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(toolCalls);
        }
        public String getToolCallId() { return toolCallId; }
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

        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public Long getCostMicrodollars() { return costMicrodollars; }
        public JsonObject getProviderUsage() { return providerUsage; }
    }
}
