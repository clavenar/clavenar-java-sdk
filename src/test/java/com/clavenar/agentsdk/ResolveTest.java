package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResolveTest {

  private static ClavenarPending pending(String baseUrl) {
    ClavenarOptions opts = Fixtures.opts(baseUrl);
    return new ClavenarPending(
        "delete_user", "c1", List.of("needs review"), () -> Transport.pollPendingOnce("c1", opts));
  }

  private static ResolveOptions fast() {
    return new ResolveOptions(Duration.ofMillis(2), Duration.ofSeconds(2));
  }

  private static String view(String decision, String note) {
    String d = decision == null ? "null" : "\"" + decision + "\"";
    String n = note == null ? "null" : "\"" + note + "\"";
    return "{\"correlation_id\":\"c1\",\"agent_id\":\"a\",\"tool_type\":\"shell\",\"method\":\"tools/call\","
        + "\"review_reasons\":[],\"requested_at\":\"2026-01-01T00:00:00Z\",\"decided_at\":null,"
        + "\"decision\":"
        + d
        + ",\"decider_note\":"
        + n
        + "}";
  }

  @Test
  void allowAfterPolls() throws Exception {
    AtomicInteger n = new AtomicInteger();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(
                    200, n.incrementAndGet() < 3 ? view(null, null) : view("allow", null)))) {
      pending(srv.baseUrl).resolve(fast());
    }
  }

  @Test
  void denyWithNote() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(200, view("deny", "too risky")))) {
      ClavenarDenied d =
          assertThrows(ClavenarDenied.class, () -> pending(srv.baseUrl).resolve(fast()));
      assertEquals("PendingDenied", d.intentCategory());
      assertEquals(List.of("too risky"), d.reasons());
      assertEquals("c1", d.correlationId());
    }
  }

  @Test
  void denyNoNote() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(200, view("deny", null)))) {
      ClavenarDenied d =
          assertThrows(ClavenarDenied.class, () -> pending(srv.baseUrl).resolve(fast()));
      assertEquals(List.of("operator denied"), d.reasons());
    }
  }

  @Test
  void terminal404() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(404, null))) {
      ClavenarTransportException e =
          assertThrows(
              ClavenarTransportException.class, () -> pending(srv.baseUrl).resolve(fast()));
      assertEquals(404, e.status());
    }
  }

  @Test
  void swallows5xxThenAllow() throws Exception {
    AtomicInteger n = new AtomicInteger();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                n.incrementAndGet() < 3
                    ? TestServer.Response.of(502, null)
                    : TestServer.Response.of(200, view("allow", null)))) {
      pending(srv.baseUrl).resolve(fast());
    }
  }

  @Test
  void deadline() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(200, view(null, null)))) {
      ClavenarTransportException e =
          assertThrows(
              ClavenarTransportException.class,
              () ->
                  pending(srv.baseUrl)
                      .resolve(new ResolveOptions(Duration.ofMillis(5), Duration.ofMillis(30))));
      assertTrue(e.getMessage().contains("not decided within"));
    }
  }

  @Test
  void badInterval() {
    ClavenarTransportException e =
        assertThrows(
            ClavenarTransportException.class,
            () ->
                pending("http://127.0.0.1:9")
                    .resolve(new ResolveOptions(Duration.ofMillis(-1), Duration.ofSeconds(1))));
    assertTrue(e.getMessage().contains("pollInterval"));
  }
}
