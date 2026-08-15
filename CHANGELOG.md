# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/) and
the project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.5.5] - 2026-08-15

### Changed

- Bind protected stack publication to the current reviewed security and
  release-contract source.

## [1.5.4] - 2026-08-09

### Changed

- Make every compiler warning release-blocking, keep the complete exception
  hierarchy explicitly serialization-versioned, and scope the third-party
  schema validator's non-validation keyword chatter out of Maven output.

## [1.5.3] - 2026-08-05

### Added

- Recoverable governed execution now loads durable state before authorization,
  cryptographically verifies every new or stored authorization, restores a
  completed outcome, and reconciles an ambiguous provider effect without
  blindly executing it again. `ClavenarRecoveryRequired` identifies intents
  whose effect cannot yet be reconciled.
- `SecureTransportProfile.reload()` atomically rotates a cached client and
  `close()` releases its connection pool and owned executor.

### Changed

- Provider shape drift, malformed terminal stream events, and malformed success
  bodies fail closed in enforce mode while observe mode reports the error and
  passes the provider response through.
- Requests, responses, tool arguments, batches, retry settings, endpoints, and
  pending correlation are validated and bounded. Credentials require HTTPS
  unless explicitly enabled for an exact loopback DEV endpoint; pending polls
  ignore only network and 5xx failures.
- Decision clients validate Lite's exact side-effect-free
  `clavenar.decision/v1` allow envelope and its correlation binding.
- Canonical execution JSON accepts only the cross-language safe numeric subset,
  uses UTF-16 object-key ordering, and bounds receipt finalization.

## [1.5.2] - 2026-07-28

### Changed

- Bind the exact external-install documentation to a new immutable source tag
  and anonymous release asset set.

## [1.5.1] - 2026-07-27

### Changed

- Republish the unchanged 1.5 SDK behavior from the exact protected source
  commit after correcting idempotent Maven registry detection.

## [1.5.0] - 2026-07-26

### Added

- `SecureTransportProfile` reloads PKCS#8 client identity and pinned trust
  sources per request, acquires the current token, and applies separate
  deadlines and explicit proxy policy across every transport path.

## [1.4.0] - 2026-07-21

### Changed

- Package the exact `clavenar.client-migration/v1` fixture and schema and
  document the client-first rollout. Inspection remains an explicit
  side-effect-free decision with its canonical pre-network request ID.

## [1.3.0] - 2026-07-21

### Changed

- Automatic retries are explicitly confined to the side-effect-free decision
  transport with one stable pre-network idempotency ID. Registered executor
  failures remain single-attempt, and the shared retry-separation fixture is
  packaged for cross-language conformance.

## [1.2.0] - 2026-07-21

### Added

- `GovernedExecutionClient` with serializable prepared requests, a registered
  executor, durable intent/completion store, workload receipt signer, and
  actual provider-result return.
- The shared `clavenar.sdk-cross-language/v1` conformance fixture, packaged in
  the Maven artifact.

### Changed

- Inspection explicitly selects `clavenar.decision/v1` with a UUID allocated
  before the first attempt and retained across safe retries. Multi-tool turns
  use one ordered atomic decision.

### Changed

- `Clavenar.wrap` now throws `ClavenarConfigException` on streaming
  calls (`createStreaming()` / `stream()`) instead of silently passing
  them through uninspected, matching the TypeScript and Python
  wrappers. Gate streamed tool calls with `StreamGate`, or set the new
  `ClavenarOptions.builder(...).allowUninspectedStream(true)` for the
  explicit opt-out.

### Added

- 429 rate-limit verdicts: the transport now parses the spec's
  `rate_limited` / `quota_exceeded` envelope instead of collapsing a
  429 into a generic `ClavenarTransportException`. Enforce mode throws
  the new `ClavenarRateLimited` (carrying `code()`, `retryAfterSecs()`,
  `reasons()`, `layer()`, `correlationId()`); observe mode surfaces the
  new `VerdictKind.RATE_LIMITED` verdict via `onVerdict` and passes the
  call through. 429s are never auto-retried — honor `retryAfterSecs()`
  in the caller.
- Shape-drift signal: a `create` response whose `stop_reason` /
  `finish_reason` declares tool use but from which zero tool calls
  were extracted logs a `System.Logger` WARNING — extraction stays
  fail-open for text-only turns, but silent provider-shape drift is
  now visible.

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
