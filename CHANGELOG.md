# Changelog

## 0.3.0 — 2026-08-21

### Added

- `x-tr-client` header channel (client telemetry contract v1). Inference-plane calls to
  TrustedRouter hosts now carry a content-free, per-attempt `x-tr-client` header (attempt index
  plus the previous attempt's outcome, error class, host, timing, and failover state) so the
  gateway can measure client-side reliability. Control-plane calls, absolute metadata fetches,
  custom base URLs, and opted-out clients send nothing. Opt out with
  `TrustedRouterOptions.builder().telemetry(false)`, `TRUSTEDROUTER_TELEMETRY=0`, or
  `DO_NOT_TRACK=1`. The header is SDK-reserved: caller-supplied `x-tr-client` values are stripped
  on every path, and a last-position network interceptor (`ReservedHeader`) re-validates the value
  against the contract grammar, stamps it on exactly the first wire request of each attempt so
  OkHttp's own 503/408 and redirect follow-ups are never counted as extra attempts, and never
  forwards it to a non-TrustedRouter host. Telemetry never fails a request. Beacons are not part
  of this release.
- Alias-domain failover. Requests to the default API host fall back to `api.allyrouter.com` and
  `api.uptimerouter.com` (`TrustedRouter.ALIAS_API_BASE_URLS`) on connection failures and on
  502/503/504; a 500 is retried on the same host because inference is not idempotent. Custom base
  URLs are never rewritten, and `regionalFailover(false)` still pins to one host.
- `x-should-retry` is honored, so the gateway can mark a 5xx as unsafe to re-send; `retry-after-ms`
  is parsed and wins over `Retry-After`.
- Attestation rollout pins: `TrustRelease.getAcceptedImageDigests()` /
  `getAcceptedImageReferences()` and `AttestationPolicy.Builder.expectedImageDigests(...)` /
  `expectedImageReferences(...)` accept the published rollout set, and
  `AttestationPolicy.fromTrustRelease` uses it.

### Changed

- **User-Agent format.** The SDK now sends `trusted-router-java/<version> java/<runtime>`, where
  `<runtime>` is `java.version` sanitized to the contract's `[0-9A-Za-z.+-]{1,24}` grammar
  (`1.8.0_452` becomes `1.8.0-452`; the `java/` token is omitted when `java.version` is empty).
  0.2.x appended `os.name` ("Mac OS X"), which fails the telemetry contract's §3.1 grammar and
  left every request's identity unparsed at the enclave.
- `TrustedRouter.VERSION` now reports the released version. It had stayed at `0.1.0` through the
  0.2.x releases, so their User-Agent reported `trusted-router-java/0.1.0`.
- Transport refactored into one engine with a pure decision kernel (`internal/RetryPolicy`,
  `Sleeper`, `CandidateUrls`, `RequestFactory`, `ErrorClassifier`, `AttestationHttp`). Public API
  and import paths are unchanged.

### Fixed

- `Retry-After` is bounded to 60 seconds (`RetryPolicy.MAX_RETRY_AFTER_SECONDS`).
  `Retry-After: Infinity` previously parsed to a ~292-million-year sleep.
- An attestation policy that pins no image identity is refused (fail-closed) by both the builder
  and the verifier; `AttestationPolicy.pinsImageIdentity()` added.
- `regionalFailover` now only controls host movement; a pinned client still retries 502/503/504
  in place instead of giving up.
- One SDK attempt is one physical request: OkHttp's status-driven follow-ups (503 with
  `Retry-After: 0`, 408, 421 on a coalesced HTTP/2 connection) can no longer re-send a request
  behind the retry policy's back.
- Credential-free requests (status, trust release, OAuth) stay credential-free at the wire even
  when an injected `OkHttpClient` interceptor re-adds ambient credentials.
- The SDK no longer follows HTTP redirects (OkHttp `followRedirects` and `followSslRedirects`
  are off), so a redirect can neither carry SDK headers or credentials to another origin nor
  create a second, invisible send.
- `TrustedRouterAsyncClient` futures: `cancel(true)` now cancels the in-flight call and closes a
  stalled response body.
- SSE streams cap each line and each frame at 1 MiB (`EventStream.MAXIMUM_FRAME_BYTES`) before a
  `String` is allocated, and reject invalid UTF-8 with `InternalException` instead of decoding it
  silently.
- `NoRouteToHostException` and `BindException` classify as `connect_error` rather than `io_error`,
  matching the Python SDK.

### Other

- Pull requests and `main` are gated by the shared SDK conformance harness
  (`Lore-Hex/trusted-router-sdk-conformance`) with no scenario skips allowed.
- README documents the alias domains and the SSE frame cap. SECURITY.md moves to private
  vulnerability reporting (no email intake) with a 72-hour acknowledgement. CODEOWNERS added.

## 0.1.0

- Initial Java, Kotlin, and Android SDK.
- Synchronous and `CompletableFuture` clients.
- Chat, Responses, Messages, embeddings, Synth, models, providers, regions, credits, activity,
  billing, Broadcast, OAuth, status, trust release, and attestation support.
- Typed streaming, errors, routing/privacy filters, exact money handling, retry/failover behavior,
  and Java 8 bytecode compatibility.
