# TrustedRouter Java SDK

[![CI](https://github.com/Lore-Hex/trusted-router-java/actions/workflows/ci.yml/badge.svg)](https://github.com/Lore-Hex/trusted-router-java/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

The official Java SDK for [TrustedRouter](https://trustedrouter.com). It works from Java,
Kotlin, Android, and any JVM application.

The SDK provides:

- OpenAI compatible chat completions and streaming
- OpenAI Responses API and typed Responses SSE events
- Anthropic Messages and embeddings
- Synth, Advisor, Selector, and MapReduce orchestration tools
- Explicit ZDR, confidential, US, provider order, price, latency, and throughput routing
- Typed errors that distinguish router failures from provider failures
- Billing, credits, activity, models, providers, and Broadcast destinations
- OAuth credit delegation with PKCE and Android deep link validation
- Google Confidential Space attestation verification
- Java 8 bytecode, Android API 21+ networking, and a `CompletableFuture` async facade

Inference uses `https://api.trustedrouter.com/v1`. Control operations use
`https://trustedrouter.com/v1`. The SDK keeps those planes separate so prompt traffic never
silently falls back to the control plane.

## Install

Gradle:

```kotlin
dependencies {
    implementation("com.trustedrouter:trusted-router:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.trustedrouter</groupId>
  <artifactId>trusted-router</artifactId>
  <version>0.1.0</version>
</dependency>
```

Android apps with a minimum API below 26 should enable
[core library desugaring](https://developer.android.com/studio/write/java8-support) for the
`Duration` and `CompletableFuture` convenience APIs. The synchronous API and OkHttp transport
support Android API 21+. The artifact includes consumer R8/ProGuard rules that preserve its Gson
wire models in minified release builds.

## Java quickstart

Set `TRUSTEDROUTER_API_KEY` in the environment. Do not put a production API key in source code.

```java
import com.trustedrouter.TrustedRouterClient;
import com.trustedrouter.models.ChatCompletion;
import com.trustedrouter.requests.ChatRequest;

TrustedRouterClient client = new TrustedRouterClient(
        System.getenv("TRUSTEDROUTER_API_KEY"));

ChatCompletion completion = client.chatCompletions(ChatRequest.builder()
        .model("trustedrouter/auto")
        .message("user", "Reply with PONG")
        .maxTokens(64)
        .build());

System.out.println(completion.firstText());
System.out.println(completion.getUsage().getCostMicrodollars());
```

The complete compiling example is in [`examples/java/Quickstart.java`](examples/java/Quickstart.java).

## Kotlin

The SDK has a normal Java API, so Kotlin needs no wrapper:

```kotlin
import com.trustedrouter.TrustedRouterClient
import com.trustedrouter.requests.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val client = TrustedRouterClient(System.getenv("TRUSTEDROUTER_API_KEY"))

val completion = withContext(Dispatchers.IO) {
    client.chatCompletions(
        ChatRequest.builder()
            .model("trustedrouter/fast")
            .message("user", "Reply with PONG")
            .maxTokens(64)
            .build()
    )
}

println(completion.firstText())
```

Alternatively, use `client.async().chatCompletions(request)` to receive a
`CompletableFuture<ChatCompletion>`.

## Android keys and OAuth

Never embed a reusable server API key in an APK. It can be extracted. For a user-facing app,
use TrustedRouter OAuth credit delegation so each user authorizes a limited key.

```java
OAuthAuthorizeOptions options = OAuthAuthorizeOptions
        .builder("myapp://oauth/trustedrouter")
        .keyLabel("My Android app")
        .limit("5.00")
        .usageLimitType("monthly")
        .build();

TrustedRouterClient publicClient = new TrustedRouterClient(
        TrustedRouterOptions.builder().build());
OAuthAuthorization authorization = publicClient.createOAuthAuthorization(options);

// Open authorization.getUrl() in a Custom Tab and retain authorization securely.
```

When Android returns to the app:

```java
OAuthCallback callback = OAuth.parseCallback(
        intent.getData().toString(), authorization.getState());

OAuthToken token = publicClient.exchangeOAuthKey(
        callback.getCode(),
        authorization.getCodeVerifier(),
        authorization.getCodeChallengeMethod());

TrustedRouterClient userClient = new TrustedRouterClient(token.getKey());
```

`OAuth.parseCallback` rejects state mismatch, denial, malformed callbacks, and missing codes.
Store the delegated key with Android Keystore backed storage. Do not log it.

## Streaming

Streams are blocking and closeable. Read them on a worker thread and always use
try-with-resources.

```java
ChatRequest request = ChatRequest.builder()
        .model("z-ai/glm-5.2")
        .message("user", "Explain TLS exporters in three paragraphs")
        .build();

try (TextStream stream = client.chatCompletionsText(request)) {
    for (String text = stream.read(); text != null; text = stream.read()) {
        System.out.print(text);
    }
}
```

For content, thinking, tools, and usage, consume typed chunks:

```java
try (EventStream<ChatCompletionChunk> stream = client.chatCompletionsChunks(request)) {
    for (ChatCompletionChunk chunk = stream.read(); chunk != null; chunk = stream.read()) {
        for (ChatCompletionChunk.Choice choice : chunk.getChoices()) {
            String thinking = choice.getDelta().getReasoning();
            String content = choice.getDelta().getContent();
            if (thinking != null) System.out.print(thinking);
            if (content != null) System.out.print(content);
        }
        if (chunk.getUsage() != null) {
            System.out.println("cost microdollars="
                    + chunk.getUsage().getCostMicrodollars());
        }
    }
}
```

The parser requires the final `[DONE]` marker. A truncated or empty SSE connection raises an
`InternalException` instead of looking like a successful empty answer.

## Responses API

```java
ResponseObject response = client.responses(ResponsesRequest.builder()
        .model("moonshotai/kimi-k3")
        .instructions("Be concise")
        .input("Give me three names for a database migration tool")
        .parameter("store", false)
        .parameter("max_output_tokens", 300)
        .build());
```

Streaming:

```java
try (EventStream<ResponseEvent> events = client.responsesEvents(request)) {
    for (ResponseEvent event = events.read(); event != null; event = events.read()) {
        if ("response.output_text.delta".equals(event.getEvent())) {
            System.out.print(event.getData().get("delta").getAsString());
        }
    }
}
```

Use `responsesInputTokens(request)` for stateless input token counting.

## Images

Use the normal OpenAI content-part shape. Public HTTPS URLs and base64 data URLs are accepted by
the TrustedRouter gateway and normalized inside the attested prompt path.

```java
JsonArray content = new JsonArray();
JsonObject text = new JsonObject();
text.addProperty("type", "text");
text.addProperty("text", "What is in this image?");
content.add(text);

JsonObject image = new JsonObject();
image.addProperty("type", "image_url");
JsonObject imageUrl = new JsonObject();
imageUrl.addProperty("url", "https://example.com/photo.jpg");
image.add("image_url", imageUrl);
content.add(image);

JsonObject message = new JsonObject();
message.addProperty("role", "user");
message.add("content", content);

ChatCompletion result = client.chatCompletions(ChatRequest.builder()
        .model("google/gemini-3.1-flash-image-preview")
        .message(message)
        .build());
```

The SDK does not impose an artificial request-size limit and has a regression test for a 1.25 MB
multimodal payload. The gateway and selected model still enforce their documented limits.

## Function tools

Tool definitions pass through with the OpenAI shape. Your application executes its own tools.

```java
JsonObject function = new JsonObject();
function.addProperty("name", "get_weather");
function.addProperty("description", "Get weather for a city");
JsonObject parameters = new JsonObject();
parameters.addProperty("type", "object");
JsonObject properties = new JsonObject();
JsonObject city = new JsonObject();
city.addProperty("type", "string");
properties.add("city", city);
parameters.add("properties", properties);
function.add("parameters", parameters);

JsonObject tool = new JsonObject();
tool.addProperty("type", "function");
tool.add("function", function);

ChatCompletion result = client.chatCompletions(ChatRequest.builder()
        .model("moonshotai/kimi-k3")
        .message("user", "What is the weather in Miami?")
        .tool(tool)
        .build());
```

Read `result.getChoices().get(0).getMessage().getToolCalls()` and send the tool result in the next
request.

## Synth

`FusionRequest` is the typed Java representation of TrustedRouter Synth. The older Fusion name is
retained for API compatibility.

```java
FusionRequest request = FusionRequest.builder()
        .message("user", "Review this migration plan for correctness")
        .analysisModels(Arrays.asList(
                "minimax/minimax-m3",
                "~kimi/latest",
                "~zai/glm-latest"))
        .judgeModel("minimax/minimax-m3")
        .fallbackJudges(Arrays.asList("~kimi/latest"))
        .fallbackFinalModels(Arrays.asList("~zai/glm-latest"))
        .selectionStrategy(TrustedRouter.SELECTION_SYNTHESIZE_NON_REFUSALS)
        .panelPrompt("Identify concrete defects")
        .synthesisPrompt("Return one actionable answer")
        .build();

ChatCompletion answer = client.synth(request);
```

Synth gets a 10 minute SDK timeout unless the call supplies an explicit timeout.

The atomic orchestration tool helpers are also available:

```java
ChatRequest.builder().advisor(advisorParameters);
ChatRequest.builder().fusion(synthParameters);
TrustedRouter.orchestrationTool("trustedrouter:selector", selectorParameters);
TrustedRouter.orchestrationTool("trustedrouter:mapreduce", mapReduceParameters);
```

## Provider selection and privacy

Typed filters avoid misspelled privacy promises:

```java
ChatRequest request = ChatRequest.builder()
        .model("z-ai/glm-5.2")
        .message("user", "Review this contract clause")
        .provider(ProviderPreferences.confidential())
        .build();
```

Available hard filters:

```java
ProviderPreferences.zeroDataRetention(); // provider.min_privacy = "zdr"
ProviderPreferences.confidential();      // confidential compute plus provider E2EE
ProviderPreferences.unitedStates();      // US-headquartered provider
```

Compose routing controls when needed:

```java
ProviderPreferences preferences = ProviderPreferences.builder()
        .only("tinfoil", "phala")
        .order("tinfoil", "phala")
        .minimumPrivacy("confidential")
        .allowFallbacks(true)
        .sort("throughput")
        .usage("credits")
        .build();
```

`confidential` is stronger than ZDR. It requires provider-side confidential compute and
provider-side end-to-end encryption and fails closed when no eligible route exists.

## Workspaces, retries, and idempotency

```java
TrustedRouterClient client = new TrustedRouterClient(TrustedRouterOptions.builder()
        .apiKey(System.getenv("TRUSTEDROUTER_API_KEY"))
        .workspaceId("workspace-id")
        .maxRetries(2)
        .build());

CallOptions call = CallOptions.builder()
        .workspaceId("different-workspace")
        .idempotencyKey("job-42-step-7")
        .build();
```

Workspace overrides are sent as `X-TrustedRouter-Workspace`, never in the prompt body. Mutating
SDK operations receive an automatic idempotency key when the caller does not provide one. Retries
use exponential backoff with jitter and honor numeric `Retry-After` values.

## Errors

```java
try {
    client.chatCompletions(request);
} catch (RateLimitException error) {
    System.err.println(error.getLayer());  // router, gateway, or provider
    System.err.println(error.getSource()); // selected upstream when supplied
    System.err.println(error.getRetryAfterSeconds());
} catch (AuthenticationException error) {
    // Invalid or disabled TrustedRouter key.
} catch (TrustedRouterException error) {
    // Other typed API, gateway, provider, or transport failure.
}
```

Error payloads are preserved. Prompts, outputs, API keys, and BYOK keys are never added to SDK
logs because the SDK has no request-body logger.

## Exact money

Costs are integer microdollars in response usage. Checkout amounts are decimal strings. Do not use
`double` for billing:

```java
Long costMicrodollars = completion.getUsage().getCostMicrodollars();

CheckoutResponse checkout = client.billingCheckout(BillingCheckoutRequest.builder()
        .amount("25.000001")
        .paymentMethod("stripe")
        .build());
```

## Attestation

```java
TrustRelease release = client.trustRelease();
AttestationPolicy policy = AttestationPolicy.fromTrustRelease(release);
GatewayAttestation attestation = client.verifyGatewayAttestation(policy);
```

The verifier checks the Google signature, issuer, audience, expiration, production debug state,
Confidential Space software identity, Secure Boot, confidential hardware class, published image
digest/reference, a fresh nonce, and the TLS leaf certificate returned by the exact attestation
request. Debug Confidential Space workloads are rejected by default.

`AttestationVerifier.verify` also accepts caller-supplied RFC 9266 TLS exporter bytes through
`AttestationVerificationOptions.tlsExporter(...)`. Java runtimes before JDK 25 do not expose a
standard TLS exporter API, so applications requiring exporter-bound verification should supply
the exporter from their TLS provider or use the TrustedRouter Go verifier. Do not describe a
certificate-only Java verification as exporter-bound.

## Raw and extensible APIs

Typed response objects retain the complete top-level JSON through `getRaw()`. Request builders
accept arbitrary JSON parameters for newly introduced provider fields. Low-level `request` and
`rawRequest` methods are also available.

Low-level paths must be relative. This prevents an accidental API-key-bearing request to a foreign
absolute URL. Status and trust metadata have dedicated credential-free absolute fetches.

## Build

```bash
./gradlew clean check javadoc
```

CI compiles with JDK 17 and emits Java 8 bytecode. It runs on Linux, macOS, and Windows, compiles
the Java quickstart, checks Javadocs, and enforces the starting coverage floor.

The credential-free production trust smoke verifies public status, release metadata, and a fresh
gateway attestation:

```bash
./gradlew runPublicTrustSmoke
```

`./gradlew runAuthenticatedSmoke` additionally verifies chat and Responses, streaming and
non-streaming, when `TRUSTEDROUTER_API_KEY` is set. These live smokes are separate from deterministic
CI tests.

See [PARITY.md](PARITY.md) for endpoint coverage and [RELEASING.md](RELEASING.md) for Maven Central
release setup.

## License

Apache License 2.0. See [LICENSE](LICENSE).
