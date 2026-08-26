# SDK parity

This table is a release gate for the public Java SDK. It tracks the supported surface of the
TrustedRouter Python and JavaScript SDKs as of Java SDK 0.1.0.

| Area | Java API | Sync | Async | Streaming |
|---|---|---:|---:|---:|
| Chat completions | `chatCompletions` | Yes | Yes | Typed chunks, text, raw |
| Synth and Fusion | `synth`, `fusion` | Yes | Yes | Through chat stream methods |
| Responses | `responses` | Yes | Yes | Typed events, raw |
| Responses input tokens | `responsesInputTokens` | Yes | Yes | N/A |
| Anthropic Messages | `messages` | Yes | Yes | Raw API escape hatch |
| Embeddings | `embeddings` | Yes | Yes | N/A |
| Models and filters | `models` | Yes | Yes | N/A |
| Providers | `providers` | Yes | Yes | N/A |
| Regions | `regions` | Yes | Yes | N/A |
| Credits | `credits` | Yes | Yes | N/A |
| Activity | `activity` | Yes | Yes | N/A |
| Billing checkout | `billingCheckout`, `stablecoinCheckout` | Yes | Yes | N/A |
| Broadcast destinations | CRUD plus test | Yes | Yes | N/A |
| Browser session | `authSession`, `logout`, `userInfo` | Yes | Yes | N/A |
| OAuth credit delegation | PKCE authorize, callback, exchange, user info | Yes | Yes | N/A |
| Status and trust release | `status`, `trustRelease` | Yes | Yes | N/A |
| Attestation | `attestation`, `verifyGatewayAttestation` | Yes | Yes | N/A |
| Inference receipts | `ReceiptVerifier.verifyReceipt` | Yes | N/A | `ReceiptCapture` |
| Raw extension surface | API and control request/raw request | Yes | Yes | Raw response |

## Behavioral parity gates

- Inference and control-plane URLs remain separate.
- Per-call workspaces become `X-TrustedRouter-Workspace`, never JSON body fields.
- Retry behavior is bounded and honors numeric `Retry-After`.
- Mutating SDK helpers use idempotency keys.
- SSE streams fail closed on malformed events, empty streams, or a missing `[DONE]` marker.
- Errors retain HTTP status, router/provider layer, source, retry delay, and raw payload.
- Costs remain integer microdollars; checkout amounts remain decimal strings.
- OAuth uses PKCE and validates callback state exactly.
- Metadata fetches cannot inherit API-key, cookie, workspace, or idempotency headers.
- Attestation rejects debug workloads and validates the nonce, image, Google signature, and exact
  TLS leaf certificate.
- Request builders preserve unknown JSON fields so new provider features do not require an SDK
  release.

## Intentional runtime differences

The async API uses a caller-configurable executor and returns `CompletableFuture`. It is an async
facade over the same blocking OkHttp implementation, which keeps one audited transport. Cancelling
the future does not promise cancellation of an in-flight network call.

Java before JDK 25 has no standard TLS-exporter API. Certificate-bound attestation works on the
supported Java 17 surface. Exporter-bound verification is available when the application supplies
exporter bytes from its TLS provider; otherwise use the TrustedRouter Go verifier for that stronger
session-binding mode.
