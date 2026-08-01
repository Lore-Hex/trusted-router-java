# Contributing

Use JDK 17 to build. Published classes target Java 8 bytecode.

```bash
./gradlew clean check javadoc
```

Changes to public client methods must update `PublicApiParityTest` and `PARITY.md`. Changes to
transport, authentication, retries, OAuth, SSE, money, or attestation require focused regression
tests. Tests must be deterministic and must not call production providers.

Keep API keys, prompts, outputs, signing keys, and provider credentials out of fixtures and logs.
Use MockWebServer for HTTP contract tests.
