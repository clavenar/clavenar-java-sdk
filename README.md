# clavenar-java-sdk

[![CI](https://github.com/clavenar/clavenar-java-sdk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/clavenar/clavenar-java-sdk/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.clavenar/agent-sdk.svg)](https://central.sonatype.com/artifact/com.clavenar/agent-sdk)

Java SDK for [Clavenar](https://clavenar.com). Inspect the tool calls a
model emits against your policies *before* your agent runs them.

Part of the by-language agent-wrapper SDK family alongside
[`@clavenar/agent-sdk`](https://github.com/clavenar/clavenar-typescript-sdk)
(TypeScript) and
[`clavenar-agent-sdk`](https://github.com/clavenar/clavenar-python-sdk)
(Python) — all speak the same wire contract.

## Install

Maven:

```xml
<dependency>
  <groupId>com.clavenar</groupId>
  <artifactId>agent-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.clavenar:agent-sdk:1.0.0")
```

Requires Java 17+. The only runtime dependency is Jackson; the SDK takes
**no dependency on the Anthropic or OpenAI SDKs** — it duck-types their
responses.

## Two ways to integrate

### 1. Inspect at the tool-dispatch boundary (recommended for frameworks)

Spring AI and LangChain4j own the model call, so gate the tool *before*
it executes. This is the primary surface:

```java
var inspector = new ClavenarInspector(
    ClavenarOptions.builder("http://localhost:8088").token(token).build());

// Spring AI ToolCallback / LangChain4j ToolExecutor, inside your tool body:
inspector.enforce(toolName, toolCallId, argumentsJson); // throws ClavenarDenied on a policy block
// ... reached only when the call cleared policy — run the tool
```

`enforce` throws `ClavenarDenied` / `ClavenarPending` in enforce mode; in
observe mode it never throws and fires your callbacks instead.

### 2. Wrap the model client (wrap-and-forget)

```java
import com.anthropic.client.AnthropicClient;

AnthropicClient client = Clavenar.wrap(
    rawAnthropicClient,
    ClavenarOptions.builder("http://localhost:8088").build());

// Use the client exactly as before; every tool_use is inspected first.
var message = client.messages().create(params); // throws ClavenarDenied on a block
```

The same `Clavenar.wrap` detects an OpenAI client
(`chat().completions().create()`) structurally. It returns a dynamic
proxy of the same interface type and passes every non-`create` method
through unchanged.

## Verdicts and the error model

`ClavenarInspector.inspect` returns a `Verdict` (`ALLOW` / `DENY` /
`PENDING`). `inspectAll`, `enforce`, and the wrap facade translate, in
enforce mode, to unchecked exceptions rooted at `ClavenarException`:

| Exception | Meaning |
|---|---|
| `ClavenarDenied` | policy rejected the call — `toolName`, `reasons`, `reviewReasons`, `intentCategory`, `layer`, `correlationId` |
| `ClavenarPending` | parked for human review — call `resolve()` to block until decided |
| `ClavenarTransportException` | clavenar unreachable / unexpected response — `status()` (0 = network) |
| `ClavenarConfigException` | bad options, or a model tool call with unparseable arguments |

## Debugging a denial

`ClavenarDenied` carries `reasons()`, `layer()`, and `correlationId()`. To
see *which detector* fired, run the gateway with
`CLAVENAR_PROXY_VERBOSE_VERDICTS=true` (Lite: `--verbose-verdicts`) — the
deny then carries a per-detector `detail()` breakdown, and the SDK renders
it to stderr when you set `devMode(true)`:

```java
var opts = ClavenarOptions.builder("https://clavenar.internal")
    .devMode(true) // dev/staging only — detailed denials are an attacker oracle
    .build();
// On a deny, the SDK prints a panel to stderr:
//   ━━ clavenar denied: send_email ━━
//     layer=brain  intent=Exfiltration  correlation=abc-123
//     detectors:
//       persona_drift         0.12
//       injection             0.91  ⚠ flagged
//     degraded: injection
```

Programmatic access (no `devMode` needed):

```java
catch (ClavenarDenied e) {
  if (e.detail() != null) {
    e.detail().detectors().stream()
        .filter(d -> d.flagged() || d.score() >= 0.5)
        .forEach(d -> System.out.println("fired: " + d.detector()));
  }
}
```

`detail()` is null unless the gateway opts in; without it the panel prints
a hint to enable verbose verdicts.

## Enforce vs observe

```java
var opts = ClavenarOptions.builder(endpoint)
    .observe()
    .onVerdict((verdict, ctx) -> log.info("{} -> {}", ctx.toolName(), verdict.kind()))
    .build();
```

Observe never blocks: verdicts surface via `onVerdict`, transport
failures via `onPolicyError`, and every call passes through — the
rollout knob for tuning policies against live traffic.

## Pending review

```java
try {
  inspector.enforce(toolName, id, argsJson);
} catch (ClavenarPending pending) {
  pending.resolve(); // blocks; returns on approve, throws ClavenarDenied on deny
}
```

## Streaming

`StreamGate` holds a tool call's closing event until clavenar returns a
verdict, so a denied call never reaches your loop as actionable. Drive it
from your stream-reading loop with `start` / `update` / `close` (Anthropic
block index) or `closeByPrefix` (OpenAI per-choice drain). See
[`docs/SEQUENCES.md`](docs/SEQUENCES.md).

## Realtime

```java
Verdict v = Realtime.inspect(
    new Realtime.FunctionCallDone(callId, name, argumentsJson), opts);
```

## Behavior parity

Matches the TypeScript reference 1:1 on the wire — see
[`docs/PARITY.md`](docs/PARITY.md) for the map and the additive Java-idiom
differences.

## License

[Apache-2.0](LICENSE).
