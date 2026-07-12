<!-- public repo — do not add internal topology, secrets, deploy/runbook, strategy, or absolute host paths -->
# clavenar-java-sdk — agent-side wrapper SDK (Maven `com.clavenar:agent-sdk`, Java 17)

Inspect the tool calls a model emits against your Clavenar policies *before* your agent runs
them. Sibling of the TypeScript (`@clavenar/agent-sdk`) and Python (`clavenar-agent-sdk`)
wrappers — same wire contract. Jackson-only; **no dependency on the Anthropic / OpenAI SDKs**
(it duck-types their response shapes).

## Build, test, lint

```bash
mvn -B verify                # build + test (runs spotless:check)
mvn -B test                  # test only
mvn -B spotless:apply        # format (google-java-format + removeUnusedImports)
mvn -B -DskipTests package   # SBOM (CycloneDX → target/bom.json / target/bom.xml)
```

CI (`.github/workflows/ci.yml`) runs `mvn -B verify` on a JDK 17 + 21 matrix, plus the SBOM job.
Examples under `examples/` are a separate Maven reactor (`examples/pom.xml`); root `mvn verify`
and CI do NOT build them — run `mvn -B -f examples/pom.xml verify` to check example changes.

Run: library, no binary. Public-API entry points: `ClavenarInspector` (`inspect` / `inspectAll`
/ `enforce`), `Clavenar.wrap`, `StreamGate`, `Realtime.inspect`. The SDK is a *client* of a
running gateway — `ClavenarOptions.builder(endpoint)` endpoint is required (no default); examples
point at the proxy on `http://localhost:8088`. Wire: `POST /mcp` to inspect, `GET /pending/{id}`
to poll a parked call.

## Layout
All source under `src/main/java/com/clavenar/agentsdk/` (Automatic-Module-Name
`com.clavenar.agentsdk`):
- `ClavenarInspector.java` — primary surface. Gate a tool at the dispatch boundary (Spring AI /
  LangChain4j): `inspect` returns a `Verdict`; `enforce` throws in enforce mode.
- `Clavenar.java` — `wrap` wrap-and-forget facade. Returns a dynamic `Proxy` over a provider
  client; structural detection (`messages()` → Anthropic, `chat()` → OpenAI); inspects
  `create()`, throws on streaming calls (`createStreaming()` / `stream()`), passes every other
  method through. `extractAnthropic` / `extractOpenAI` duck-type tool calls out of the response
  tree.
- `Transport.java` — HTTP: `POST /mcp` inspection (with retry), `GET /pending/{id}` polling.
- `StreamGate.java` — holds a tool call's closing event until a verdict returns (`start` /
  `update` / `close` for Anthropic block index, `closeByPrefix` for OpenAI per-choice drain).
- `Realtime.java` — single function-call inspection (`Realtime.inspect`).
- `ClavenarOptions.java` — builder: endpoint (required), `token`, `observe()` vs enforce,
  `devMode`, retry/timeout, `onVerdict` / `onPolicyError` callbacks.
- `Verdict` / `VerdictKind` (`ALLOW`/`DENY`/`PENDING`) / `VerdictContext` / `VerdictDetail` —
  result + per-detector breakdown.
- `ClavenarException` family — `ClavenarDenied`, `ClavenarPending`, `ClavenarTransportException`,
  `ClavenarConfigException`.
- `NormalizedToolCall.java` — the normalized `(id, name, args)` shape extraction produces.
- `Json.java` (shared Jackson `MAPPER`), `Mode`, `DevMode`, `ResolveOptions`, `RetryOptions`,
  `ClavenarPendingView`.
- Tests in `src/test/java/...` (JUnit 5); `TestServer` / `Fixtures` back the transport tests.

## Conventions & invariants
- **Inspection-before-execution is the contract.** A call must be inspected and clear policy
  *before* the tool runs. In enforce mode a block throws (`ClavenarDenied`) and a parked call
  throws `ClavenarPending` (call `resolve()` to wait for the human decision); in observe mode
  nothing throws — verdicts surface via `onVerdict`, transport errors via `onPolicyError`.
- **Fail-closed.** In enforce mode a transport failure to reach the gateway throws
  `ClavenarTransportException` rather than silently allowing the call.
- **`Clavenar.wrap` blocks streaming calls.** It inspects the non-streaming `create()`; a
  `createStreaming()` / `stream()` call through the proxy throws `ClavenarConfigException`
  (matching the TS/Python wrappers) — gate streamed tool calls with `StreamGate`, or set
  `allowUninspectedStream(true)` for the explicit, dangerous opt-out.
- **Duck-typed extraction can silently return zero calls.** `extractAnthropic` / `extractOpenAI`
  read provider response shapes structurally; a shape mismatch (provider-SDK change, unexpected
  JSON) yields an empty list, so the call clears inspection by default. A turn whose
  `stop_reason` / `finish_reason` declares tool use but extracts zero calls logs a
  `System.Logger` WARNING (shape-drift signal). Treat a provider upgrade as a parity risk and
  cover it with a fixture.
- **`wrap` needs an interface-based client** — it builds a `java.lang.reflect.Proxy` over the
  client's interfaces; a non-interface client throws `ClavenarConfigException`. Use
  `ClavenarInspector` directly in that case.
- **No provider dependency.** Runtime deps are the JDK + Jackson only. Don't add the Anthropic or
  OpenAI SDK — keep extraction duck-typed.
- **`devMode(true)` is dev/staging only.** It renders per-detector denial detail to stderr;
  detailed denials are an attacker oracle. `VerdictDetail` is null unless the gateway opts in
  (`CLAVENAR_PROXY_VERBOSE_VERDICTS=true`).
- **No secrets at rest.** Options hold only the endpoint URL and an optional bearer token,
  supplied by the caller per process.

Java coding standards (this stack):
- Format with **google-java-format** via spotless; run `mvn -B spotless:apply` before committing.
  `spotless:check` is bound to the build, so unformatted code or unused imports fail `mvn verify`.
- Compiler runs `-Xlint:all` — keep it warning-clean; don't suppress to silence a real warning.
- **JDK 17 is the baseline** (`maven.compiler.release` = 17) and CI also builds on 21 — don't use
  APIs newer than 17.
- Anything in a `public` method/field signature must itself be `public` (no leaking
  package-private types through the public API).
- Tests are JUnit 5 (`org.junit.jupiter`) under `src/test/java`.

## Pointers
README.md · SECURITY.md · docs/SEQUENCES.md (StreamGate event ordering) · docs/PARITY.md
(1:1 map vs. the TypeScript reference + Java-idiom additions) · CHANGELOG.md · CONTRIBUTING.md
(release / Maven Central flow).
