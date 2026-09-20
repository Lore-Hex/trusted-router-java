# Boundary audit (pre-edit inventory)

Representation: Gson 2.13.2 JsonElement/JsonObject trees, followed by reflective Gson model decoding. No decoded Map casts, raw JSON collections, Optional.get, Objects.requireNonNull, Objects.toString, or wire-string switches occur in library source. Line numbers in this inventory refer to the original checkout. “Fixed” identifies the selected fix, before implementation.

| Class | Original site(s) | Verdict and invariant |
|---|---|---|
| 1,2,4 | models/ModelDecoder.java:11–15 (all client model endpoints) | Fixed: reject non-object envelopes with an SDK error; validate consumed/producer-guaranteed fields before Gson; project optional metadata without coercion while retaining original raw JSON. Do not require invented fields. |
| 1,2 | oauth/OAuthToken.java:8–13; models/UserInfoResponse.java:5–21 | Fixed at decoder: exchange requires string key, optional object identity/data, nullable identity strings; userinfo requires object data, nullable sub/email. Legacy null sub is valid. |
| 1,2 | models/ChatCompletion.java:27–30,61; models/ChatCompletionChunk.java:26–29 | Fixed at decoder: choices entries and consumed text must have valid shapes; absent/null text and structured message content remain valid. |
| 1,2 | models/ModelList.java:22–25 | Fixed at decoder: validate entries and consumed id; nullable metadata passes through. |
| 2 | models/TrustRelease.java:9–24; attestation/AttestationPolicy.java:44–62 | Fixed at decoder: image pins must be strings/string arrays; empty identity still rejected by existing policy. |
| 1,2 | TrustedRouterClient.java:232 | Fixed: SSE fallback type must be a string when consumed. Explicit SSE event name takes precedence. |
| 1,4 | TrustedRouterClient.java:410 | Fixed: status object guard with typed error. |
| 2 | attestation/AttestationVerifier.java:295–302,310–337 | Fixed: prohibit string/number coercion and integer truncation; reject malformed trust arrays rather than dropping members. |
| 2 | internal/JsonSupport.java:57–62; errors/TrustedRouterException.java:59–69 | Fixed: malformed optional error metadata must not be stringified; preserve raw payload and status classification. |
| 3 | internal/RequestFactory.java:146–153 | Fixed: group case-insensitively, preserve same-layer repeated values, replace lower-layer values on call override. |
| 3 | TrustedRouterOptions.java:20,38,88,153; CallOptions.java:19,29,64,120 | Intentionally unchanged: public single-value Map API is storage only; case variants retained until case-insensitive wire merge. Exact duplicate keys cannot be represented by this existing API. |
| 3 | internal/ReservedHeader.java:197–225; internal/CredentialGuard.java:36; internal/RetryPolicy.java; internal/ErrorClassifier.java:122–143; receipts/ReceiptVerifier.java (header reads) | Already safe: OkHttp Headers implements case-insensitive reads/removals and retains repetitions; reserved stripping removes all variants. |
| 3 | models/BroadcastDestination.java:15,23; requests/BroadcastDestinationRequest.java:29 | Intentionally unchanged: JSON configuration metadata, not a local HTTP header lookup/merge. |
| 3 | internal/TelemetryReporter.java:147,974 | Already safe: SDK-created unique headers, handed to OkHttp; no caller/wire header map lookup. |
| 1,4 | requests/FusionRequest.java:21,24; requests/ChatRequest.java:35–36 | Already safe: private arrays contain copied JsonObjects only; stream_options checks shape before access. Null builder arguments are programmer errors. |
| 1,2,4 | receipts/ReceiptVerifier.java:173–265,316–389,486–603,752–761,856–975 | Already safe: strict JSON/UTF-8, guarded shapes, string primitives, integer grammar/range, typed receipt exceptions; broad parse catch only translates to same receipt error family. |
| 1,4 | attestation/AttestationVerifier.java:107–156,274–291,305–307 | Already safe: JWKS/JWT object checks, typed attestation errors; boolean secure boot type check; missing container causes pin mismatch. |
| 1,4 | streaming/EventStream.java:96–126 | Already safe: parses object before mapper, translates malformed JSON to InternalException, closes then rethrows mapper failures. |
| 1,2 | internal/Transport.java:358–376 | Already safe: provider/model shape and primitive kind checked; optional telemetry/routing hints default intentionally. |
| 2,4 | internal/JsonSupport.java:28–36; internal/ErrorClassifier.java:26–63,122–143 | Already safe: invalid body produces typed failure on success, preserves HTTP classification on error; malformed retry hint falls back to bounded backoff. |
| 1,2 | internal/Telemetry.java:755–806; internal/TelemetryReporter.java:292,564–565,1177–1204 | Already safe/intentionally unchanged: generated fallback objects have guaranteed keys; remote telemetry policy is advisory, finite/range checked, malformed hints ignored (must not fail API calls). |
| 4 | internal/RequestRecorder.java:408,491,540,603,616,628,649,677; internal/TelemetryReporter.java:505,923,939,1006,1269,1274,1308; internal/Transport.java:305; internal/ReservedHeader.java:185,197,216 | Intentionally unchanged: telemetry isolation catches cannot turn a malformed API body into success or retry; reserved fallback strips forged header. |
| 4 | TrustedRouterAsyncClient.java:214; internal/Transport.java:277 | Already safe: complete future exceptionally / finish telemetry and rethrow, no outcome laundering. |
| 2 | TrustedRouterClient.java:468; oauth/OAuth.java:103–180 | Already safe: query serialization after null check, not decoded JSON coercion; callback map is URL query parameters, duplicate keys rejected, not HTTP headers. |
| 1 | TrustedRouterClient.java:74; attestation/AttestationPolicy.java:43; attestation/AttestationVerifier.java:46,66–68; receipts/ReceiptVerifier.java:68–69,83,91; receipts/ReceiptCapture.java:19,53; requests/ChatRequest.java:130,EmbeddingsRequest.java:32,ResponsesRequest.java:49,MessagesRequest.java:42; TrustedRouter.java:126 | Intentionally unchanged: explicit non-null public-argument preconditions are programmer-contract checks, not unchecked traversal of decoded JSON. No optional wire metadata is passed into these preconditions; the wire parsers reject missing consumed values first. |
| 1 | internal/RequestRecorder.java:244; internal/Transport.java:386; attestation/AttestationVerifier.java:154; receipts/ReceiptVerifier.java:603 | Already safe: casts dominated by instanceof, no decoded collection casts. |

## Full search inventory

The companion `boundary-search.txt` records the complete pre-edit class sweep (including safe internal collections and generated JSON). The table groups related sites by the invariant above; it is not a claim that stock Error Prone performs wire taint analysis.

## Static configuration and limits

`build.gradle.kts` applies net.ltgt.errorprone 4.3.0 with Error Prone 2.42.0 (the last series supporting a JDK 17 compiler, per the [plugin compatibility table](https://github.com/tbroyer/gradle-errorprone-plugin#requirements)). All default checks retain their default severity, with existing `-Xlint:all -Werror` making warnings fail compilation. Explicit ERROR checks: UnusedVariable, MissingCasesInEnumSwitch, ReturnValueIgnored, FutureReturnValueIgnored, CatchAndPrintStackTrace, EmptyCatch, ClassCanBeStatic. UnnecessaryParentheses is OFF as requested.

NullAway 0.12.10 is OFF by default. A stable `./gradlew compileJava -PnullawayAudit --rerun-tasks` with AnnotatedPackages=com.trustedrouter produced 345 distinct diagnostic locations (358 diagnostics). The inventory includes 187 uninitialized reference-field sites alone, far beyond the approximately ten annotation-site budget. No nullability annotations were added. See [diagnostics](nullaway-audit.txt) and [field locations](nullaway-field-sites.txt). The opt-in audit remains reproducible.

`boundaryCheck` runs before compileJava, hence before tests and as part of check. Its source rules reject new decoded casts, Gson extraction/decoding, input stringification/numeric parsing, header-map operations, broad catches, switches and Optional.get sites. Existing operations have exact source/count reviews and reasons in `scripts/boundary-reviewed.json`; stale and duplicated reviews fail. See the [complete current operation table](boundary-sites.md). This conservative lexical gate is **not** a general taint or null-dominance analysis: reviewed operations still require runtime proofs and review when their surrounding guards change. It deliberately flags newly introduced operations even when they may be safe. It does not prove arbitrary differently-written Java boundary code correct.

Tests and standalone examples retain javac `-Xlint:all -Werror`; Error Prone applies to production source only. There are **zero @SuppressWarnings annotations** in production or tests. Two old test suppressions were removed: the unused CounterIncrement helper was deleted, and ScriptedRandom now declares serialVersionUID. Error Prone cleanup preserves behavior: guarded casts use pattern variables, nullable-string equality uses explicit null checks, query splitting explicitly requests its existing zero-limit semantics, and telemetry map signatures use Map while retaining LinkedHashMap implementations.

All analysis dependencies are annotation processors only; runtime dependencies and publication API dependencies remain unchanged. Public method signatures are unchanged. The additive InvalidResponseException is an unchecked SDK exception for direct ModelDecoder callers; HTTP client paths translate it to the existing checked InternalException. Raw JSON is retained in full; optional metadata that cannot fit an existing typed field is absent from the typed view rather than coerced or rejected. Nullable legacy identities remain supported.

## Mechanized proof

`scripts/mutation-check` delegates to the Python-stdlib-only `scripts/mutation_check.py`. Each run copies the checkout to a temporary directory, validates that every recorded before-pattern occurs exactly once, establishes a passing baseline, then applies one mutant at a time. Original bytes are held in memory and restored in finally (never git checkout); the real checkout is never mutated. Each focused invocation uses `./gradlew test --offline --tests fully.qualified.Class.method`, reuses the Gradle daemon, and skips static checking only in that isolated copy so a compiler failure cannot masquerade as a test kill. A kill requires fresh JUnit XML naming the expected failing method. Compile/infrastructure failures are ERROR, successful mutant tests are SURVIVED, stale patterns are fatal. Results and logs are written under build/mutation-results.

CI explicitly runs static compilation, then check/javadoc, then runtime mutations and static negative controls. Release verification also runs the mutation gate. Python is a development/CI prerequisite only.

The static negative controls reintroduce one example of each source-gate category and every promoted Error Prone rule in an isolated copy. All 14 are rejected; see [results](static-gate-results.txt). The source checks are exercised directly; Error Prone checks compile with the source gate excluded to prove Error Prone itself catches them. Wall time: 18.145 seconds.

Shared fixture: src/test/resources/auth-wire-fixtures.json is copied verbatim, SHA-256 `ba492afe81f7616bca062ab7ed35f70d42042e6f6f60794ac9e2a599574df1d2`. The single OAuthTest.sharedAuthWireFixturesUseRealClientParsing test sends every exchange/userinfo accept and reject payload through the actual client/server path, checks consumed fields, retains the entire original tree, and checks HTTP method/path. A dedicated mutant requiring exchange.data proves literal producer fixtures catch invented requirements.

## Local verification

- `./gradlew clean check javadoc`: PASS (33 seconds); 256 tests, 0 failures, 0 errors, 2 existing optional ReceiptLiveSmokeTest cases skipped (live credentials/environment required). Coverage verification and examples compile passed.
- `python3 scripts/mutation_check.py`: PASS, **29/29 killed in 58.75 seconds**, immediately after the full CI command above. Daemon reused; no batching necessary. [Each mutation, source location, focused test and time](mutation-results.md); [machine-readable results](mutation-results.json).
- `./gradlew generatePomFileForMavenPublication`: PASS. Generated POM lists only existing OkHttp 5.3.0 and Gson 2.13.2 API dependencies; no analysis/runtime dependency leakage.
- Negative controls for the mutation runner: stale before-pattern exits 1; an intentionally surviving no-op mutation exits 1. [Captured output](runner-failure-controls.txt).
- Supplied conformance harness with `--sdk java --sdk-root java=$PWD`: **25 checks, 25 passed, 0 failed, 0 skipped**. Loopback binding succeeded; not sandbox-blocked. [Full report](conformance-results.txt).
- No suppression annotations remain. No commits were made.

The writable temporary GRADLE_USER_HOME was `/tmp/tr-java-gradle` because the sandbox denied writes to the default home cache. JAVA_HOME used the supplied Homebrew JDK 17 path. Local MockWebServer binds succeeded. The Gradle filesystem-watcher warning is an environment limitation and did not prevent compilation or tests.

[Every changed file and final line location](change-locations.md).
