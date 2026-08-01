import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.models.ResponseEvent;
import com.trustedrouter.models.ResponseObject;
import com.trustedrouter.requests.ChatRequest;
import com.trustedrouter.requests.ResponsesRequest;
import com.trustedrouter.streaming.EventStream;
import com.trustedrouter.streaming.TextStream;
import java.util.Locale;

/** Small authenticated production smoke for chat and Responses, including SSE. */
public final class AuthenticatedSmoke {
    private AuthenticatedSmoke() {}

    public static void main(String[] args) throws Exception {
        String apiKey = requireEnvironment("TRUSTEDROUTER_API_KEY");
        String model = System.getenv("TRUSTEDROUTER_MODEL");
        if (model == null || model.isEmpty()) {
            model = "trustedrouter/fast";
        }
        TrustedRouterClient client = new TrustedRouterClient(apiKey);

        ChatRequest chat = ChatRequest.builder()
                .model(model)
                .message("user", "Reply exactly PONG")
                .maxTokens(64)
                .build();
        ChatCompletion completion = client.chatCompletions(chat);
        requirePong(completion.firstText(), "chat");

        StringBuilder streamedText = new StringBuilder();
        try (TextStream stream = client.chatCompletionsText(chat)) {
            for (String text = stream.read(); text != null; text = stream.read()) {
                streamedText.append(text);
            }
        }
        requirePong(streamedText.toString(), "chat stream");

        ResponsesRequest responses = ResponsesRequest.builder()
                .model(model)
                .input("Reply exactly PONG")
                .parameter("store", false)
                .parameter("max_output_tokens", 64)
                .build();
        ResponseObject response = client.responses(responses);
        if (!"completed".equals(response.getStatus()) || response.getOutput().isEmpty()) {
            throw new IllegalStateException("Responses request did not complete");
        }

        boolean completed = false;
        try (EventStream<ResponseEvent> stream = client.responsesEvents(responses)) {
            for (ResponseEvent event = stream.read(); event != null; event = stream.read()) {
                if ("response.completed".equals(event.getEvent())) {
                    completed = true;
                }
            }
        }
        if (!completed) {
            throw new IllegalStateException("Responses stream had no completed event");
        }
        System.out.println("authenticated_chat=ok");
        System.out.println("authenticated_chat_stream=ok");
        System.out.println("authenticated_responses=ok");
        System.out.println("authenticated_responses_stream=ok");
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Set " + name);
        }
        return value;
    }

    private static void requirePong(String value, String operation) {
        if (value == null || !value.toUpperCase(Locale.ROOT).contains("PONG")) {
            throw new IllegalStateException(operation + " did not return PONG");
        }
    }
}
