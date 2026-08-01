import com.trustedrouter.TrustedRouterClient
import com.trustedrouter.requests.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun main() {
    val apiKey = checkNotNull(System.getenv("TRUSTEDROUTER_API_KEY"))
    val client = TrustedRouterClient(apiKey)
    val completion = withContext(Dispatchers.IO) {
        client.chatCompletions(
            ChatRequest.builder()
                .model("trustedrouter/fast")
                .message("user", "Reply with PONG")
                .maxTokens(16)
                .build()
        )
    }
    println(completion.firstText())
}
