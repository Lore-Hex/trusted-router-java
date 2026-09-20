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
    public ChatCompletion.Usage getUsage() { return usage; }

    /**
     * Performs the text delta operation.
     *
     * @return the text delta
     */
    public String textDelta() {
        if (choices == null || choices.isEmpty() || choices.get(0).delta == null) {
            return "";
        }
        return choices.get(0).delta.content == null ? "" : choices.get(0).delta.content;
    }

    /**
     * Represents choice.
     */
    public static final class Choice {
        private int index;
        private Delta delta;
        private String finishReason;
        /**
         * Returns index.
         *
         * @return the index
         */
        public int getIndex() { return index; }
        /**
         * Returns delta.
         *
         * @return the delta
         */
        public Delta getDelta() { return delta; }
        /**
         * Returns finish reason.
         *
         * @return the finish reason
         */
        public String getFinishReason() { return finishReason; }
    }

    /**
     * Represents delta.
     */
    public static final class Delta {
        private String role;
        private String content;
        private String reasoning;
        private List<JsonObject> toolCalls;
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
        public String getContent() { return content; }
        /**
         * Returns reasoning.
         *
         * @return the reasoning
         */
        public String getReasoning() { return reasoning; }
        /**
         * Returns tool calls.
         *
         * @return the tool calls
         */
        public List<JsonObject> getToolCalls() {
            return toolCalls == null
                    ? Collections.<JsonObject>emptyList() : Collections.unmodifiableList(toolCalls);
        }
    }
}
