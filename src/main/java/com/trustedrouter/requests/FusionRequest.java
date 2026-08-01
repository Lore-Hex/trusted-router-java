package com.trustedrouter.requests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.trustedrouter.CallOptions;
import com.trustedrouter.TrustedRouter;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A Synth/Fusion panel request with judge and synthesizer fallback controls. */
public final class FusionRequest {
    private final ChatRequest chatRequest;

    private FusionRequest(Builder builder) {
        ChatRequest.Builder chat = ChatRequest.builder().model(builder.modelAlias);
        for (JsonElement message : builder.messages) {
            chat.message(message.getAsJsonObject());
        }
        for (JsonElement tool : builder.tools) {
            chat.tool(tool.getAsJsonObject());
        }
        chat.fusion(builder.fusionParameters);
        for (Map.Entry<String, JsonElement> entry : builder.topLevel.entrySet()) {
            chat.parameter(entry.getKey(), entry.getValue());
        }
        CallOptions options = builder.callOptions == null ? CallOptions.NONE : builder.callOptions;
        if (!options.hasTimeoutOverride()) {
            options = options.toBuilder()
                    .timeout(Duration.ofMillis(TrustedRouter.DEFAULT_FUSION_TIMEOUT_MILLIS))
                    .build();
        }
        chat.callOptions(options);
        chatRequest = chat.build();
    }

    public static Builder builder() { return new Builder(); }
    public ChatRequest toChatRequest() { return chatRequest; }

    public static final class Builder {
        private String modelAlias = TrustedRouter.SYNTH_MODEL;
        private final JsonArray messages = new JsonArray();
        private final JsonArray tools = new JsonArray();
        private final JsonObject fusionParameters = new JsonObject();
        private final JsonObject topLevel = new JsonObject();
        private CallOptions callOptions;
        private Builder() {}

        public Builder modelAlias(String value) { modelAlias = value; return this; }
        public Builder message(String role, String content) {
            messages.add(ChatRequest.message(role, content));
            return this;
        }
        public Builder message(JsonObject value) { messages.add(value.deepCopy()); return this; }
        public Builder tool(JsonObject value) { tools.add(value.deepCopy()); return this; }
        public Builder enabled(boolean value) {
            fusionParameters.addProperty("enabled", value); return this;
        }
        public Builder analysisModels(List<String> values) {
            fusionParameters.add("analysis_models", TrustedRouter.stringArray(values)); return this;
        }
        public Builder judgeModel(String value) {
            fusionParameters.addProperty("model", value); return this;
        }
        public Builder selectionStrategy(String value) {
            fusionParameters.addProperty("selection_strategy", value); return this;
        }
        public Builder fallbackJudges(List<String> values) {
            fusionParameters.add("fallback_judges", TrustedRouter.stringArray(values)); return this;
        }
        public Builder fallbackFinalModels(List<String> values) {
            fusionParameters.add("fallback_final_models", TrustedRouter.stringArray(values));
            return this;
        }
        public Builder maxCompletionTokens(int value) {
            fusionParameters.addProperty("max_completion_tokens", value); return this;
        }
        public Builder maxToolCalls(int value) {
            fusionParameters.addProperty("max_tool_calls", value); return this;
        }
        public Builder preset(String value) {
            fusionParameters.addProperty("preset", value); return this;
        }
        public Builder panelPrompt(String value) {
            fusionParameters.addProperty("panel_prompt", value); return this;
        }
        public Builder synthesisPrompt(String value) {
            fusionParameters.addProperty("synthesis_prompt", value); return this;
        }
        public Builder finalPrompt(String value) {
            fusionParameters.addProperty("final_prompt", value); return this;
        }
        public Builder parameter(String name, JsonElement value) {
            if ("model".equals(name) || "messages".equals(name) || "tools".equals(name)
                    || "stream".equals(name)) {
                throw new IllegalArgumentException(name + " is managed by FusionRequest");
            }
            topLevel.add(name, value == null ? JsonNull.INSTANCE : value.deepCopy());
            return this;
        }
        public Builder parameter(String name, String value) {
            return parameter(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
        }
        public Builder parameter(String name, Number value) {
            return parameter(name, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
        }
        public Builder callOptions(CallOptions value) { callOptions = value; return this; }
        public FusionRequest build() { return new FusionRequest(this); }
    }
}
