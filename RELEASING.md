# Releasing

- Ensure `main` is green.
- Update `version` in `gradle.properties`.
- Tag the release: `git tag vX.Y.Z && git push --tags`.
- CI (`.github/workflows/release.yml`) will:
    - build, sign, and publish to Maven Central.

## Required secrets (GitHub repository)

- `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY_BASE64` (ASCII-armored PGP private key encoded to Base64)
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

Local publish (optional):

```bash
ORG_GRADLE_PROJECT_mavenCentralUsername=... \
ORG_GRADLE_PROJECT_mavenCentralPassword=... \
ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private.asc)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=... \
./gradlew publishToMavenCentral --no-daemon
```
