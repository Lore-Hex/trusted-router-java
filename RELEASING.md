# Releasing

Releases are built from versioned Git tags by `.github/workflows/release.yml` and published to Maven
Central as `com.trustedrouter:trusted-router`.

## One-time Maven Central setup

1. Create or use the Lore Hex Corp account in the Maven Central Publisher Portal.
2. Verify ownership of the `com.trustedrouter` namespace.
3. Generate a Central Portal publishing token.
4. Generate a password-protected OpenPGP signing key for releases.
5. Add these GitHub Actions secrets:
   - `MAVEN_CENTRAL_USERNAME`
   - `MAVEN_CENTRAL_PASSWORD`
   - `SIGNING_KEY_ID`
   - `SIGNING_KEY`
   - `SIGNING_KEY_PASSWORD`

`SIGNING_KEY` contains the ASCII-armored private key. Keep the private signing key out of the
repository and local build logs.

## Release procedure

1. Update `TrustedRouter.VERSION`, `CHANGELOG.md`, README dependency examples, and the default
   `VERSION_NAME` together.
2. Run `./gradlew clean check javadoc generatePomFileForMavenPublication`.
3. Inspect the generated POM and release diff.
4. Create and push an annotated tag such as `v0.1.0` from a green `main` commit.
5. Confirm the release workflow publishes and Maven Central reports the deployment as published.
6. Resolve `com.trustedrouter:trusted-router:<version>` from clean Java and Android samples before
   announcing the release.

Never republish a changed artifact under an existing version.
