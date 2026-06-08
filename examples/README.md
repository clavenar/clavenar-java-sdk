# Examples

Two ways to integrate, mirroring the TypeScript and Python SDKs:

1. **Inspect at the tool-dispatch boundary** (recommended for LangChain4j /
   Spring AI) — build `NormalizedToolCall`s and inspect before running the
   tools. See [`custom-dispatcher`](custom-dispatcher).
2. **Wrap the model client** — one line at boot; every tool call is
   inspected before the response returns. See
   [`native-anthropic`](native-anthropic) and [`native-openai`](native-openai).

Plus [`realtime`](realtime) for the OpenAI Realtime websocket surface.

This is a standalone Maven reactor depending on the locally installed SDK,
so the published artifact stays provider-dependency-free. Build the SDK
first, then the examples:

```bash
mvn -DskipTests install            # install com.clavenar:agent-sdk to ~/.m2
mvn -f examples/pom.xml compile
```

Each module has a runnable `Main` (reads `CLAVENAR_ENDPOINT`, default a
local [clavenar-lite](https://github.com/clavenar/clavenar-lite) at
`http://localhost:8088`):

```bash
mvn -f examples/pom.xml -pl native-anthropic \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.clavenar.examples.nativeanthropic.Main
```
