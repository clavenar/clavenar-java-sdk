# Sequences

How the SDK behaves on each wire path. It is a client of
[clavenar-lite](https://github.com/clavenar/clavenar-lite)'s
`POST /mcp` + `GET /pending/{id}` surface.

## Single inspect — `ClavenarInspector.inspect`

1. Serialize the JSON-RPC envelope `{jsonrpc,method:"tools/call",params:{name,arguments},id}`.
2. `POST {endpoint}/mcp` with a per-request timeout (default 10s).
3. Map the response: `200` → allow (read `X-Clavenar-Correlation-Id`),
   `403` → deny (normalize the envelope), `202` → pending
   (`correlationId = header ?? body`), anything else → transport error.
4. Network errors and 5xx retry up to `maxAttempts` with full-jitter
   backoff; `200` / `403` / other `4xx` never retry.

`inspect` returns a `Verdict` and never throws on a deny — the caller
decides.

## Batch inspect — `ClavenarInspector.inspectAll` / `enforce`

1. Fan out one inspection per call on the common pool, then **await all**.
2. In enforce mode, any transport error surfaces here (fail closed)
   before any deny is processed — matching Promise.all semantics.
3. Process resolved results in **submission order**: `onVerdict` fires per
   call, then the first `DENY` → `ClavenarDenied` / `PENDING` →
   `ClavenarPending`. Observe mode never throws; a per-call transport
   failure fires `onPolicyError` and is treated as allowed.

## Streaming gate — `StreamGate`

Driven from the stream-reading loop:

1. Tool-call opening → `start(key, id, name)`.
2. Argument fragments → `update(key, …)`.
3. The closing event (Anthropic `content_block_stop`, the OpenAI
   `finish_reason:"tool_calls"` chunk) → `close(key)` / `closeByPrefix`
   **before** the event is forwarded. The gate assembles the buffered
   call(s) and runs `inspectAll`, throwing on an enforce-mode deny so the
   wrapper stops the stream before releasing the closing event. Empty
   arguments assemble to `{}`; unparseable arguments throw
   `ClavenarConfigException`.

## Pending resolve — `ClavenarPending.resolve`

1. Poll `GET /pending/{id}` every `pollInterval` (default 2s) until the
   deadline (default 10m), using a monotonic clock.
2. `decision:"allow"` → return; `decision:"deny"` → `ClavenarDenied`
   (`intentCategory="PendingDenied"`, reason = decider note or
   `"operator denied"`).
3. `401` / `404` are terminal; `5xx` and network blips are swallowed and
   retried on the next tick.

## Realtime — `Realtime.inspect`

Normalize a `response.function_call_arguments.done` event into a
`NormalizedToolCall` (arguments forwarded as a raw JSON string if they
don't parse) and run a single inspect. Returns the `Verdict`.
