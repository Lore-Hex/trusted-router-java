package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trustedrouter.models.BroadcastDestination;
import com.trustedrouter.models.CheckoutResponse;
import com.trustedrouter.models.EmbeddingResponse;
import com.trustedrouter.models.MessagesResponse;
import com.trustedrouter.models.ResponseInputTokens;
import com.trustedrouter.models.ResponseObject;
import com.trustedrouter.requests.BillingCheckoutRequest;
import com.trustedrouter.requests.BroadcastDestinationRequest;
import com.trustedrouter.requests.EmbeddingsRequest;
import com.trustedrouter.requests.MessagesRequest;
import com.trustedrouter.requests.ResponsesRequest;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

final class EndpointSurfaceTest {
    @Test void inferenceEndpointShapesMatchSiblingSdks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"object\":\"list\",\"data\":[{\"index\":0,"
                    + "\"object\":\"embedding\",\"embedding\":[0.1,0.2]}],"
                    + "\"model\":\"embed\",\"usage\":{\"prompt_tokens\":2,"
                    + "\"completion_tokens\":0,\"total_tokens\":2}}"));
            server.enqueue(json("{\"id\":\"msg1\",\"type\":\"message\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"text\","
                    + "\"text\":\"ok\"}],\"model\":\"anthropic/test\","
                    + "\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}"));
            server.enqueue(json("{\"id\":\"resp1\",\"object\":\"response\","
                    + "\"status\":\"completed\",\"output\":[]}"));
            server.enqueue(json("{\"input_tokens\":11,\"total_tokens\":11}"));
            TrustedRouterClient client = client(server);

            EmbeddingResponse embeddings = client.embeddings(EmbeddingsRequest.builder()
                    .model("embed").input("hello").build());
            assertThat(embeddings.getData().get(0).getEmbedding().getAsJsonArray()).hasSize(2);
            assertPath(server.takeRequest(), "/v1/embeddings");

            MessagesResponse messages = client.messages(MessagesRequest.builder()
                    .model("anthropic/test").message("user", "hello").build());
            assertThat(messages.getContent().get(0).get("text").getAsString()).isEqualTo("ok");
            assertPath(server.takeRequest(), "/v1/messages");

            ResponseObject response = client.responses(ResponsesRequest.builder()
                    .model("test").input("hello").parameter("store", false).build());
            assertThat(response.getStatus()).isEqualTo("completed");
            assertPath(server.takeRequest(), "/v1/responses");

            ResponseInputTokens tokens = client.responsesInputTokens(
                    ResponsesRequest.builder().model("test").input("hello").build());
            assertThat(tokens.getInputTokens()).isEqualTo(11);
            assertPath(server.takeRequest(), "/v1/responses/input_tokens");
        }
    }

    @Test void billingUsesExactStringAndBroadcastSecretsAreWriteOnly() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"url\":\"https://pay.example/session\",\"status\":\"open\"}"));
            server.enqueue(json("{\"id\":\"dest1\",\"type\":\"posthog\","
                    + "\"name\":\"analytics\",\"enabled\":true}"));
            TrustedRouterClient client = client(server);

            CheckoutResponse checkout = client.billingCheckout(BillingCheckoutRequest.builder()
                    .amount("25.000001").paymentMethod("stripe").build());
            assertThat(checkout.getStatus()).isEqualTo("open");
            RecordedRequest billing = server.takeRequest();
            assertPath(billing, "/v1/billing/checkout");
            assertThat(billing.getBody().readUtf8()).contains("\"amount\":\"25.000001\"");

            BroadcastDestination destination = client.createBroadcastDestination(
                    BroadcastDestinationRequest.builder("posthog").name("analytics")
                            .apiKey("ph-secret").includeContent(false).build());
            assertThat(destination.getId()).isEqualTo("dest1");
            assertThat(destination.getRaw().toString()).doesNotContain("ph-secret");
            RecordedRequest create = server.takeRequest();
            assertPath(create, "/v1/broadcast/destinations");
            assertThat(create.getBody().readUtf8()).contains("ph-secret");
        }
    }

    @Test void modelFiltersUseOpenRouterCompatibleQueryNames() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"data\":[]}"));
            client(server).models(com.trustedrouter.requests.ModelFilters.builder()
                    .openWeights(true).providerJurisdiction("US")
                    .providerRegion("us-west-2").build());
            String path = server.takeRequest().getPath();
            assertThat(path).contains("open_weights=true")
                    .contains("provider%5Bjurisdiction%5D=US")
                    .contains("provider%5Bregion%5D=us-west-2");
        }
    }

    @Test void bodylessPostEndpointsSendAnEmptyBodyInsteadOfFailingInOkHttp() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("{\"data\":{\"deleted\":true}}"));
            server.enqueue(json("{\"ok\":true}"));
            TrustedRouterClient client = client(server);

            assertThat(client.logout().getData().getAsJsonObject()
                    .get("deleted").getAsBoolean()).isTrue();
            RecordedRequest logout = server.takeRequest();
            assertThat(logout.getMethod()).isEqualTo("POST");
            assertThat(logout.getBodySize()).isZero();

            assertThat(client.testBroadcastDestination("destination-1", null)
                    .getAsJsonObject().get("ok").getAsBoolean()).isTrue();
            RecordedRequest test = server.takeRequest();
            assertThat(test.getMethod()).isEqualTo("POST");
            assertThat(test.getBodySize()).isZero();
        }
    }

    private static TrustedRouterClient client(MockWebServer server) {
        return new TrustedRouterClient(TrustedRouterOptions.builder().apiKey("sk-test")
                .baseUrl(server.url("/v1").toString())
                .controlBaseUrl(server.url("/v1").toString()).maxRetries(0).build());
    }
    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
    private static void assertPath(RecordedRequest request, String expected) {
        assertThat(request.getPath()).isEqualTo(expected);
    }
}
