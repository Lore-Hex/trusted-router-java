package com.trustedrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustedrouter.errors.InternalException;
import com.trustedrouter.models.ChatCompletionChunk;
import com.trustedrouter.models.ResponseEvent;
import com.trustedrouter.requests.ChatRequest;
import com.trustedrouter.requests.ResponsesRequest;
import com.trustedrouter.streaming.EventStream;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

final class StreamingTest {
    @Test void chatStreamingParsesChunksUsageAndDone() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hel\"}}]}\n\n"
                    + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,"
                    + "\"total_tokens\":3}}\n\ndata: [DONE]\n\n"));
            TrustedRouterClient client = client(server);
            try (EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build())) {
                assertThat(stream.read().textDelta()).isEqualTo("hel");
                ChatCompletionChunk last = stream.read();
                assertThat(last.textDelta()).isEqualTo("lo");
                assertThat(last.getUsage().getTotalTokens()).isEqualTo(3);
                assertThat(stream.read()).isNull();
            }
        }
    }

    @Test void responsesStreamingPreservesNamedAndDataTypeEvents() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("event: response.created\n"
                    + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"r1\"}}\n\n"
                    + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"
                    + "data: [DONE]\n\n"));
            try (EventStream<ResponseEvent> stream = client(server).responsesEvents(
                    ResponsesRequest.builder().input("hi").build())) {
                assertThat(stream.read().getEvent()).isEqualTo("response.created");
                assertThat(stream.read().getEvent()).isEqualTo("response.output_text.delta");
                assertThat(stream.read()).isNull();
            }
        }
    }

    @Test void streamErrorEnvelopeFailsInsteadOfReturningEmptySuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: {\"error\":{\"message\":\"provider failed\"}}\n\n"));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());
            assertThatThrownBy(stream::read)
                    .isInstanceOf(InternalException.class)
                    .hasMessage("provider failed");
        }
    }

    @Test void malformedSseFailsClosed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: {not-json}\n\n"));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());
            assertThatThrownBy(stream::read)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Malformed");
        }
    }

    @Test void unexpectedEofCannotMasqueradeAsACompletedStream() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: {\"id\":\"c1\",\"choices\":[]}\n\n"));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());
            assertThat(stream.read().getId()).isEqualTo("c1");
            assertThatThrownBy(stream::read)
                    .isInstanceOf(InternalException.class)
                    .hasMessageContaining("before [DONE]");
            assertThat(stream.isFinished()).isTrue();
        }
    }

    @Test void nonObjectSseDataFailsClosed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse("data: \"not an event object\"\n\n"));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());
            assertThatThrownBy(stream::read)
                    .isInstanceOf(InternalException.class)
                    .hasMessageContaining("JSON object");
        }
    }

    @Test void oversizedSseLineFailsBeforeItCanBeMaterializedUnbounded() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String body = "data: " + repeat('x', EventStream.MAXIMUM_FRAME_BYTES) + "\n\n";
            server.enqueue(sse(body));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());

            assertThatThrownBy(stream::read)
                    .isInstanceOf(InternalException.class)
                    .hasMessageContaining("exceeded")
                    .hasMessageContaining(String.valueOf(EventStream.MAXIMUM_FRAME_BYTES));
            assertThat(stream.isFinished()).isTrue();
        }
    }

    @Test void cumulativeSseFrameLimitCoversManyIndividuallyBoundedLines() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            int half = EventStream.MAXIMUM_FRAME_BYTES / 2;
            String body = "data: " + repeat('x', half) + "\n"
                    + "data: " + repeat('y', half) + "\n\n";
            server.enqueue(sse(body));
            EventStream<ChatCompletionChunk> stream = client(server).chatCompletionsChunks(
                    ChatRequest.builder().message("user", "hi").build());

            assertThatThrownBy(stream::read)
                    .isInstanceOf(InternalException.class)
                    .hasMessageContaining("line or frame exceeded");
            assertThat(stream.isFinished()).isTrue();
        }
    }

    private static TrustedRouterClient client(MockWebServer server) {
        return new TrustedRouterClient(TrustedRouterOptions.builder()
                .apiKey("sk-test").baseUrl(server.url("/v1").toString())
                .controlBaseUrl(server.url("/v1").toString()).maxRetries(0).build());
    }
    private static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
