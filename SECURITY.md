# Security

## Reporting a vulnerability

Email **security@clavenar.com** with details and a reproduction. Please do
not open a public issue for security reports. We aim to acknowledge within
two business days.

## Posture

- **No provider dependency.** The SDK depends only on the JDK and Jackson;
  it duck-types the Anthropic / OpenAI response shapes rather than pulling
  their SDKs, keeping the supply-chain surface minimal.
- **Fail-closed by default.** In enforce mode a transport failure to reach
  clavenar throws `ClavenarTransportException` rather than silently
  allowing the call.
- **No secrets at rest.** The SDK holds only the endpoint URL and an
  optional bearer token, both supplied by the caller per process.
- **Supply chain.** CI emits a CycloneDX SBOM and runs an OWASP dependency
  scan; releases are GPG-signed and published to Maven Central.

## Supported versions

The latest minor release receives security fixes.
