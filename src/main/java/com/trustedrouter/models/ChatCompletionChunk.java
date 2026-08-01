package com.trustedrouter.models;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;

/** One OpenAI-compatible chat completion SSE chunk. */
public final class ChatCompletionChunk extends JsonModel {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private ChatCompletion.Usage usage;

    public String getId() { return id; }
    public String getObject() { return object; }
    public long getCreated() { return created; }
    public String getModel() { return model; }
    public List<Choice> getChoices() {
        return choices == null ? Collections.<Choice>emptyList() : Collections.unmodifiableList(choices);
    }
    public ChatCompletion.Usage getUsage() { return usage; }

    public String textDelta() {
        if (choices == null || choices.isEmpty() || choices.get(0).delta == null) {
            return "";
        }
        return choices.get(0).delta.content == null ? "" : choices.get(0).delta.content;
    }

    public static final class Choice {
        private int index;
        private Delta delta;
        private String finishReason;
        public int getIndex() { return index; }
        public Delta getDelta() { return delta; }
        public String getFinishReason() { return finishReason; }
    }

    public static final class Delta {
        private String role;
        private String content;
        private String reasoning;
        private List<JsonObject> toolCalls;
        public String getRole() { return role; }
        public String getContent() { return content; }
        public String getReasoning() { return reasoning; }
        public List<JsonObject> getToolCalls() {
            return toolCalls == null
                    ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(toolCalls);
        }
    }
}
