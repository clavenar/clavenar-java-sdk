package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class RealtimeTest {

  @Test
  void normalizeValidJson() {
    NormalizedToolCall tc =
        Realtime.normalize(new Realtime.FunctionCallDone("call_1", "transfer", "{\"amount\":100}"));
    assertEquals("call_1", tc.id());
    assertEquals("transfer", tc.name());
    assertEquals(100, tc.input().get("amount").asInt());
  }

  @Test
  void normalizeInvalidJsonFallsBackToString() {
    NormalizedToolCall tc =
        Realtime.normalize(new Realtime.FunctionCallDone("call_2", "transfer", "not json"));
    JsonNode input = tc.input();
    assertTrue(input.isTextual());
    assertEquals("not json", input.asText());
  }

  @Test
  void inspectAllow() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(200, null))) {
      Verdict v =
          Realtime.inspect(
              new Realtime.FunctionCallDone("call_3", "f", "{}"), Fixtures.opts(srv.baseUrl));
      assertEquals(VerdictKind.ALLOW, v.kind());
    }
  }
}
