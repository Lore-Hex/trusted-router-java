package com.trustedrouter.requests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouter;
import java.util.List;

/** OpenAI-compatible chat request with TrustedRouter orchestration helpers. */
public final class ChatRequest {
    private final JsonObject body;
    private final CallOptions callOptions;

    private ChatRequest(Builder builder) {
        this.body = builder.body.deepCopy();
        if (!body.has("model")) {
            body.addProperty("model", TrustedRouter.AUTO_MODEL);
        }
        if (!body.has("messages")) {
            body.add("messages", new JsonArray());
        }
        this.callOptions = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
    }

    /**
     * Creates a builder for this value.
     *
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns a copy of the wire JSON, applying streaming options and removing client-only fields.
     *
     * @param stream whether to request a streaming response
     * @return the wire JSON representation
     */
    public JsonObject toJson(boolean stream) {
        JsonObject value = body.deepCopy();
        value.addProperty("stream", stream);
        if (stream) {
            JsonObject streamOptions = value.has("stream_options")
                    && value.get("stream_options").isJsonObject()
                    ? value.getAsJsonObject("stream_options") : new JsonObject();
            if (!streamOptions.has("include_usage")) {
                streamOptions.addProperty("include_usage", true);
            }
            value.add("stream_options", streamOptions);
        }
        stripClientOnly(value);
        return value;
    }

    /**
     * Returns call options.
     *
     * @return the call options
     */
    public CallOptions getCallOptions() { return callOptions; }

    /**
     * Creates a role/content message object.
     *
     * @param role the role
     * @param content the content
     * @return the message JSON
     */
    public static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content == null ? JsonNull.INSTANCE : new JsonPrimitive(content));
        return message;
    }

    private static void stripClientOnly(JsonObject value) {
        for (String key : new String[] {
                "api_key", "extra_headers", "idempotency_key", "timeout", "workspace_id"
        }) {
            value.remove(key);
        }
    }

    /** Mutable builder; values are copied by {@link #build()}. */
    public static final class Builder {
        private final JsonObject body = new JsonObject();
        private final JsonArray messages = new JsonArray();
        private final JsonArray tools = new JsonArray();
        private CallOptions callOptions;

        private Builder() {
            body.add("messages", messages);
        }

        /**
         * Sets model.
         *
         * @param value the model
         * @return this builder
         */
        public Builder model(String value) { body.addProperty("model", value); return this; }
        /**
         * Sets message.
         *
         * @param role the role
         * @param content the content
         * @return this builder
         */
        public Builder message(String role, String content) {
            messages.add(ChatRequest.message(role, content));
            return this;
        }
        /**
         * Sets message.
         *
         * @param value the message
         * @return this builder
         */
        public Builder message(JsonObject value) { messages.add(value.deepCopy()); return this; }
        /**
         * Sets messages.
         *
         * @param values the values
         * @return this builder
         */
        public Builder messages(List<JsonObject> values) {
            while (messages.size() > 0) { messages.remove(messages.size() - 1); }
            if (values != null) {
                for (JsonObject value : values) { message(value); }
            }
            return this;
        }
        /**
         * Sets tool.
         *
         * @param value the tool
         * @return this builder
         */
        public Builder tool(JsonObject value) {
            tools.add(value.deepCopy());
            body.add("tools", tools);
            return this;
        }
        /**
         * Sets fusion.
         *
         * @param parameters the parameters
         * @return this builder
         */
        public Builder fusion(JsonObject parameters) {
            return tool(TrustedRouter.fusionTool(parameters));
        }
        /**
         * Sets advisor.
         *
         * @param parameters the parameters
         * @return this builder
         */
        public Builder advisor(JsonObject parameters) {
            return tool(TrustedRouter.advisorTool(parameters));
        }
        /**
         * Sets selector.
         *
         * @param parameters the parameters
         * @return this builder
         */
        public Builder selector(JsonObject parameters) {
            return tool(TrustedRouter.selectorTool(parameters));
        }
        /**
         * Sets map reduce.
         *
         * @param parameters the parameters
         * @return this builder
         */
        public Builder mapReduce(JsonObject parameters) {
            return tool(TrustedRouter.mapReduceTool(parameters));
        }
        /**
         * Sets subagent.
         *
         * @param parameters the parameters
         * @return this builder
         */
        public Builder subagent(JsonObject parameters) {
            return tool(TrustedRouter.subagentTool(parameters));
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, JsonElement value) {
            body.add(name, value == null ? JsonNull.INSTANCE : value.deepCopy());
            return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, String value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
            return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, Number value) {
            body.add(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
            return this;
        }
        /**
         * Sets parameter.
         *
         * @param name the name
         * @param value the parameter
         * @return this builder
         */
        public Builder parameter(String name, boolean value) {
            body.addProperty(name, value);
            return this;
        }
        /**
         * Sets temperature.
         *
         * @param value the temperature
         * @return this builder
         */
        public Builder temperature(double value) { return parameter("temperature", value); }
        /**
         * Sets max tokens.
         *
         * @param value the max tokens
         * @return this builder
         */
        public Builder maxTokens(int value) { return parameter("max_tokens", value); }
        /**
         * Sets max completion tokens.
         *
         * @param value the max completion tokens
         * @return this builder
         */
        public Builder maxCompletionTokens(int value) {
            return parameter("max_completion_tokens", value);
        }
        /**
         * Sets provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(JsonObject value) { return parameter("provider", value); }
        /**
         * Sets provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(ProviderPreferences value) {
            if (value == null) { throw new NullPointerException("value"); }
            return provider(value.toJson());
        }
        /**
         * Sets models.
         *
         * @param values the values
         * @return this builder
         */
        public Builder models(List<String> values) {
            return parameter("models", TrustedRouter.stringArray(values));
        }
        /**
         * Sets call options.
         *
         * @param value the call options
         * @return this builder
         */
        public Builder callOptions(CallOptions value) { this.callOptions = value; return this; }
        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        public ChatRequest build() { return new ChatRequest(this); }
    }
}
