# Security

Report SDK or TrustedRouter security issues privately to `security@trustedrouter.com`. Do not open
a public issue containing credentials, prompts, outputs, exploit details, or unpublished findings.

## SDK boundaries

- The SDK sends inference only to the configured API-plane URL.
- Control-plane operations use a distinct configured URL.
- Absolute URLs are rejected by authenticated low-level request methods.
- Status and trust metadata are fetched without API-key, cookie, workspace, or idempotency headers.
- The SDK does not install request-body logging.
- Android applications must not embed reusable server API keys; use OAuth credit delegation and
  platform-backed secret storage.

Dependency and source reports should include the affected SDK version and a minimal reproducer that
does not contain live credentials.
