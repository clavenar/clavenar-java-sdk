# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/) and
the project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0]

### Added

- Dev-mode deny rendering: with `devMode(true)`, a `ClavenarDenied`
  carrying a per-detector `detail()` breakdown (gateway run with
  `CLAVENAR_PROXY_VERBOSE_VERDICTS=true`) is rendered as a panel to
  stderr. See "Debugging a denial" in the README.

## [1.0.0]

Initial release. Java port of the Clavenar agent-wrapper SDK,
behavior-compatible with `@clavenar/agent-sdk` (TypeScript) and
`clavenar-agent-sdk` (Python) on the wire.

### Added

- `ClavenarInspector` — `inspect` / `inspectAll` / `enforce` /
  `pollPendingOnce` plus async variants, the primary surface for
  LangChain4j / Spring AI tool boundaries.
- `Clavenar.wrap` — a dynamic-proxy wrap-and-forget facade over an
  interface-based Anthropic / OpenAI client (no provider dependency;
  responses are duck-typed).
- `StreamGate` streaming primitive, `Realtime` helper, `Pending.resolve`
  poll loop, enforce / observe modes with `onVerdict` / `onPolicyError`,
  retries with full-jitter backoff.
- Exception hierarchy rooted at `ClavenarException`
  (`ClavenarDenied` / `ClavenarPending` / `ClavenarConfigException` /
  `ClavenarTransportException`), all unchecked.

### Notes

- Matches the TypeScript reference where TS and Python diverge: an OpenAI
  non-streaming tool call with unparseable `arguments` throws
  `ClavenarConfigException`. See `docs/PARITY.md`.
- Java 17 baseline; the only runtime dependency is Jackson.
