import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.requests.ChatRequest;

/** Minimal TrustedRouter Java example. */
public final class Quickstart {
    private Quickstart() {}

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("TRUSTEDROUTER_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Set TRUSTEDROUTER_API_KEY");
        }
        String model = System.getenv("TRUSTEDROUTER_MODEL");
        if (model == null || model.isEmpty()) {
            model = "trustedrouter/fast";
        }
        TrustedRouterClient client = new TrustedRouterClient(apiKey);
        ChatCompletion completion = client.chatCompletions(ChatRequest.builder()
                .model(model)
                .message("user", "Reply with PONG")
                .maxTokens(64)
                .build());
        String text = completion.firstText();
        if (text == null || !text.toUpperCase(java.util.Locale.ROOT).contains("PONG")) {
            throw new IllegalStateException("expected PONG from " + model);
        }
        System.out.println("chat=ok model=" + model);
    }
}
