package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StreamGateTest {

  @Test
  void allow() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(200, null))) {
      StreamGate gate = new StreamGate(Fixtures.opts(srv.baseUrl));
      gate.start("0", "toolu_1", "delete_user");
      gate.update("0", "", "", "{\"user\":");
      gate.update("0", "", "", "\"alice\"}");
      gate.close("0");
      assertFalse(gate.has("0"));
    }
  }

  @Test
  void deny() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}"))) {
      StreamGate gate = new StreamGate(Fixtures.opts(srv.baseUrl));
      gate.start("0", "toolu_1", "delete_user");
      gate.update("0", "", "", "{}");
      assertThrows(ClavenarDenied.class, () -> gate.close("0"));
    }
  }

  @Test
  void emptyArgsBecomeObject() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              body.set(b);
              return TestServer.Response.of(200, null);
            })) {
      StreamGate gate = new StreamGate(Fixtures.opts(srv.baseUrl));
      gate.start("0", "toolu_1", "noop");
      gate.close("0");
      var args = Json.MAPPER.readTree(body.get()).get("params").get("arguments");
      assertTrue(args.isObject());
      assertEquals(0, args.size());
    }
  }

  @Test
  void unparseableArgs() {
    StreamGate gate = new StreamGate(Fixtures.opts("http://127.0.0.1:9"));
    gate.start("0", "toolu_1", "f");
    gate.update("0", "", "", "not json");
    assertThrows(ClavenarConfigException.class, () -> gate.close("0"));
  }

  @Test
  void missingIdName() {
    StreamGate gate = new StreamGate(Fixtures.opts("http://127.0.0.1:9"));
    gate.update("0", "", "", "{\"a\":1}");
    assertThrows(ClavenarConfigException.class, () -> gate.close("0"));
  }

  @Test
  void batchOrder() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(
                    403,
                    "{\"error\":\"x\",\"reasons\":[\"denied " + Fixtures.toolName(b) + "\"]}"))) {
      StreamGate gate = new StreamGate(Fixtures.opts(srv.baseUrl));
      gate.update("0:0", "id_a", "first", "{}");
      gate.update("0:1", "id_b", "second", "{}");
      ClavenarDenied d = assertThrows(ClavenarDenied.class, () -> gate.closeByPrefix("0:"));
      assertEquals("first", d.toolName());
    }
  }

  @Test
  void observeDoesNotThrow() throws Exception {
    List<VerdictKind> kinds = new java.util.ArrayList<>();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}"))) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .observe()
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .onVerdict((v, ctx) -> kinds.add(v.kind()))
              .build();
      StreamGate gate = new StreamGate(opts);
      gate.start("0", "toolu_1", "f");
      gate.update("0", "", "", "{}");
      gate.close("0");
      assertEquals(List.of(VerdictKind.DENY), kinds);
    }
  }
}
