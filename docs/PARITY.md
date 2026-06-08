# Behavior parity

The Java SDK reproduces the TypeScript reference
([`@clavenar/agent-sdk`](https://github.com/clavenar/clavenar-typescript-sdk))
byte-for-byte on the wire. These behaviors are identical across the TS,
Python, and Java SDKs:

| Behavior | Contract |
|---|---|
| Inspect request | `POST {endpoint}/mcp`, JSON-RPC 2.0 `{jsonrpc,method:"tools/call",params:{name,arguments},id}`; `arguments` forwarded verbatim |
| Auth | `Authorization: Bearer {token}` only when a token is set |
| 200 | allow; `X-Clavenar-Correlation-Id` surfaced when present |
| 403 | deny; missing `reasons`/`review_reasons` → empty, missing `intent_category` → `""`; non-string `error` → transport error |
| 202 | pending; `correlationId = header ?? body`, both empty → transport error |
| Retry | network + 5xx retry up to `maxAttempts` (default 3); full-jitter backoff `base*2^attempt*(0.5+rand*0.5)`, base 100ms; 200/403/other-4xx never retry; timeout 10s |
| Inspect-all | concurrent inspect, **submission-order** first-deny; `onVerdict` before any deny→throw |
| Enforce | first deny → `ClavenarDenied`, pending → `ClavenarPending`; transport error fails closed, `onPolicyError` not called |
| Observe | nothing blocks; per-call transport failure → `onPolicyError`, treated as allowed |
| Streaming | closing event held until verdict; empty args → `{}`; unparseable drained args → `ClavenarConfigException` |
| Resolve | poll `GET /pending/{id}` every 2s, ceiling 10m; deny → `ClavenarDenied` (`intentCategory="PendingDenied"`, reason = decider note or `"operator denied"`); 401/404 terminal; 5xx/network swallowed |
| OpenAI non-streaming, unparseable args | `ClavenarConfigException` (matches TS, not Python's raw-string fallback) |
| Realtime | `arguments` forwarded as a raw JSON string on parse failure |
| URL join | trims one trailing/leading slash; never drops a base path like `https://gw/clavenar` |

## Intentional, additive Java-idiom differences

None change wire bytes or verdict outcomes:

1. **`ClavenarException` base class.** TS/Python root their four errors at
   the language base with no shared parent. Java adds a `ClavenarException`
   root so callers can `catch (ClavenarException e)` at a tool boundary —
   each concrete type keeps the same name and fields. The exceptions are
   **unchecked**, which is decisive for the dynamic-proxy `wrap` (a checked
   exception thrown through `java.lang.reflect.Proxy` would be wrapped in
   `UndeclaredThrowableException` and break `catch (ClavenarDenied)`).
2. **Sync-primary, async-optional.** `inspect` / `inspectAll` are
   blocking; `inspectAsync` / `inspectAllAsync` return `CompletableFuture`.
   Like Python's sync/async split, the JVM can't transparently bridge a
   blocking caller into an event loop.
3. **No transparent same-type proxy guarantee on concrete clients.**
   `Clavenar.wrap` uses a `java.lang.reflect.Proxy`, which requires the
   provider client to be interface-based. The official `anthropic-java` /
   `openai-java` clients are; for anything else, use `ClavenarInspector`.
4. **No `extraHeaders` option** — matches the TS reference (the Python SDK
   has one; the Java SDK follows TS).
