# Contributing

## Verify before pushing

```bash
mvn -B verify              # compile + spotless check + JUnit 5 + CycloneDX SBOM
mvn -B spotless:apply      # auto-format (google-java-format) before committing
```

CI runs `mvn -B verify` on JDK 17 and 21.

## Conventions

- Java 17 baseline; no provider dependency in the published artifact —
  the Anthropic / OpenAI response shapes are duck-typed via Jackson.
- Behavior must stay 1:1 with the TypeScript reference on the wire — if a
  change touches wire behavior, update `docs/PARITY.md` and add a test.
- Tests run against an in-process JDK `HttpServer` (`TestServer`); no live
  network in unit tests.

## Releasing to Maven Central

One-time org setup (before the first `v*` tag):

1. Claim the `com.clavenar` namespace at
   [central.sonatype.com](https://central.sonatype.com) and verify it with
   a **DNS TXT** record on `clavenar.com` (the GitHub-based path would
   force a `io.github.clavenar` groupId — not what we want).
2. Generate a GPG key and publish the public key to a keyserver
   (`keys.openpgp.org`).
3. Add repository secrets: `CENTRAL_TOKEN_USERNAME`,
   `CENTRAL_TOKEN_PASSWORD` (the Central Portal user token),
   `GPG_PRIVATE_KEY` (armored), and `GPG_PASSPHRASE`.

Then push a tag matching the POM `<version>` (e.g. `v1.0.0`):
`release.yml` asserts the tag matches the version, re-runs the tests,
GPG-signs the source/javadoc/jar, and deploys via the Central Publishing
Portal with `autoPublish=false` (publish the first release manually from
the portal as a safety gate, then flip to `true`).
