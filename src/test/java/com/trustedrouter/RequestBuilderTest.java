package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.models.ModelDecoder;
import com.trustedrouter.requests.FusionRequest;
import com.trustedrouter.requests.ProviderPreferences;
import com.trustedrouter.requests.ResponsesRequest;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RequestBuilderTest {
    @Test void acceptsMillisecondTimeoutsWithoutDuration() {
        TrustedRouterOptions options = TrustedRouterOptions.builder()
                .timeoutMillis(1_234L)
                .build();
        CallOptions call = CallOptions.builder().timeoutMillis(567L).build();

        assertThat(options.getTimeoutMillis()).isEqualTo(1_234L);
        assertThat(call.getTimeoutMillis()).isEqualTo(567L);
        assertThatThrownBy(() -> TrustedRouterOptions.builder().timeoutMillis(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallOptions.builder().timeoutMillis(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void confidentialProviderPreferencesAreExactAndFailClosed() {
        ProviderPreferences preferences = ProviderPreferences.builder()
                .order("tinfoil", "phala")
                .only(Arrays.asList("tinfoil", "phala"))
                .ignore("unknown-disabled-provider")
                .allowFallbacks(false)
                .dataCollection("deny")
                .minimumPrivacy("confidential")
                .sort("throughput")
                .usage("credits")
                .jurisdiction("us")
                .build();
        JsonObject value = preferences.toJson();
        assertThat(value.get("min_privacy").getAsString()).isEqualTo("confidential");
        assertThat(value.get("data_collection").getAsString()).isEqualTo("deny");
        assertThat(value.getAsJsonArray("order")).hasSize(2);
        assertThat(value.get("allow_fallbacks").getAsBoolean()).isFalse();

        assertThatThrownBy(() -> ProviderPreferences.builder().minimumPrivacy("probably").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderPreferences.builder().jurisdiction("eu").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void providerPreferencesWorkAcrossResponsesAndDoNotMutateAfterBuild() {
        ProviderPreferences preferences = ProviderPreferences.confidential();
        JsonObject first = preferences.toJson();
        first.addProperty("min_privacy", "any");
        JsonObject body = ResponsesRequest.builder().input("hello")
                .provider(preferences).build().toJson(false);
        assertThat(body.getAsJsonObject("provider").get("min_privacy").getAsString())
                .isEqualTo("confidential");
    }

    @Test void firstClassSynthRequestUsesTypedToolAndLongDefaultTimeout() {
        FusionRequest request = FusionRequest.builder()
                .message("user", "Compare these approaches")
                .analysisModels(Arrays.asList("minimax/minimax-m3", "~zai/glm-latest"))
                .judgeModel("minimax/minimax-m3")
                .fallbackJudges(Arrays.asList("~kimi/latest"))
                .fallbackFinalModels(Arrays.asList("~zai/glm-latest"))
                .selectionStrategy(TrustedRouter.SELECTION_SYNTHESIZE_NON_REFUSALS)
                .panelPrompt("Focus on correctness")
                .synthesisPrompt("Return one answer")
                .maxCompletionTokens(4096)
                .build();

        JsonObject body = request.toChatRequest().toJson(false);
        assertThat(body.get("model").getAsString()).isEqualTo(TrustedRouter.SYNTH_MODEL);
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertThat(tool.get("type").getAsString()).isEqualTo("trustedrouter:fusion");
        JsonObject parameters = tool.getAsJsonObject("parameters");
        assertThat(parameters.getAsJsonArray("analysis_models")).hasSize(2);
        assertThat(parameters.get("selection_strategy").getAsString())
                .isEqualTo("synthesize_non_refusals");
        assertThat(body.get("panel_prompt").getAsString()).isEqualTo("Focus on correctness");
        assertThat(request.toChatRequest().getCallOptions().getTimeoutMillis())
                .isEqualTo(TrustedRouter.DEFAULT_FUSION_TIMEOUT_MILLIS);
    }

    @Test void synthRequestPreservesExplicitPerCallTimeout() {
        CallOptions options = CallOptions.builder().timeout(Duration.ofSeconds(9)).build();
        FusionRequest request = FusionRequest.builder().message("user", "hello")
                .callOptions(options).build();
        assertThat(request.toChatRequest().getCallOptions().getTimeoutMillis()).isEqualTo(9000L);
    }

    @Test void snakeCaseUsageAndExactMicrodollarCostDecodeCorrectly() {
        ChatCompletion value = ModelDecoder.decode(
                com.trustedrouter.internal.JsonSupport.parse("{\"choices\":[],\"usage\":{"
                        + "\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18,"
                        + "\"cost_microdollars\":1234567,\"provider_usage\":{\"provider\":\"x\"}}}"),
                ChatCompletion.class);
        assertThat(value.getUsage().getPromptTokens()).isEqualTo(11);
        assertThat(value.getUsage().getCompletionTokens()).isEqualTo(7);
        assertThat(value.getUsage().getCostMicrodollars()).isEqualTo(1_234_567L);
        assertThat(value.getUsage().getProviderUsage().get("provider").getAsString()).isEqualTo("x");
    }
}
