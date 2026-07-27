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

## Releasing

Direct tag publication is disabled. The protected stack distribution workflow
dispatches `release.yml` with the exact signed-BOM source SHA and component
version. The workflow re-runs verification, publishes
`com.clavenar:agent-sdk` to the authenticated GitHub Packages Maven registry,
and attaches the exact POM, JAR, and SBOMs to an anonymous versioned GitHub
release. Missing or substituted protected inputs fail before publication.
