package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TransportTest {

  private static Verdict inspect(ClavenarOptions opts) {
    return new ClavenarInspector(opts).inspect(Fixtures.sampleCall());
  }

  @Test
  void allow() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(200, null).corr("c1"))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertEquals(VerdictKind.ALLOW, v.kind());
      assertEquals("c1", v.correlationId());
    }
  }

  @Test
  void deny() throws Exception {
    String body =
        """
        {"error":"security_violation","reasons":["nope"],"review_reasons":["r"],"intent_category":"Destruction","layer":"policy"}""";
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(403, body).corr("c1"))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertEquals(VerdictKind.DENY, v.kind());
      assertEquals(java.util.List.of("nope"), v.reasons());
      assertEquals("Destruction", v.intentCategory());
      assertEquals("policy", v.layer());
      assertEquals("c1", v.correlationId());
    }
  }

  @Test
  void denyNormalization() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(403, "{\"error\":\"x\"}"))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertTrue(v.reasons().isEmpty());
      assertTrue(v.reviewReasons().isEmpty());
      assertEquals("", v.intentCategory());
      assertNull(v.layer());
    }
  }

  @Test
  void denyBadShapeIsTransport() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(403, "{\"foo\":1}"))) {
      ClavenarTransportException e =
          assertThrows(ClavenarTransportException.class, () -> inspect(Fixtures.opts(srv.baseUrl)));
      assertEquals(403, e.status());
    }
  }

  @Test
  void rateLimitedWithRetryAfter() throws Exception {
    AtomicInteger n = new AtomicInteger();
    String body =
        """
        {"verdict":"rate_limited","layer":"proxy","error":"rate_limited","reasons":["agent request velocity exceeded"],"correlation_id":"c-429","retry_after_secs":17}""";
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              n.incrementAndGet();
              return TestServer.Response.of(429, body);
            })) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(3, Duration.ofMillis(1)))
              .build();
      Verdict v = inspect(opts);
      assertEquals(VerdictKind.RATE_LIMITED, v.kind());
      assertEquals("rate_limited", v.rateLimitCode());
      assertEquals(Integer.valueOf(17), v.retryAfterSecs());
      assertEquals("proxy", v.layer());
      assertEquals(java.util.List.of("agent request velocity exceeded"), v.reasons());
      assertEquals("c-429", v.correlationId());
      // A 429 is a verdict, not a transient failure — exactly one attempt.
      assertEquals(1, n.get());
    }
  }

  @Test
  void quotaExceededWithoutRetryAfter() throws Exception {
    String body =
        """
        {"verdict":"quota_exceeded","layer":"proxy","error":"quota_exceeded","reasons":["tenant monthly spend cap reached"]}""";
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(429, body))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertEquals(VerdictKind.RATE_LIMITED, v.kind());
      assertEquals("quota_exceeded", v.rateLimitCode());
      assertNull(v.retryAfterSecs());
    }
  }

  @Test
  void rateLimitBadShapeIsTransport() throws Exception {
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(429, "{\"wrong\":\"shape\"}"))) {
      ClavenarTransportException e =
          assertThrows(ClavenarTransportException.class, () -> inspect(Fixtures.opts(srv.baseUrl)));
      assertEquals(429, e.status());
    }
  }

  @Test
  void pendingHeaderWins() throws Exception {
    String body = "{\"status\":\"pending\",\"correlation_id\":\"cb\",\"review_reasons\":[\"x\"]}";
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(202, body).corr("ch"))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertEquals(VerdictKind.PENDING, v.kind());
      assertEquals("ch", v.correlationId());
      assertEquals(java.util.List.of("x"), v.reviewReasons());
    }
  }

  @Test
  void pendingBodyFallback() throws Exception {
    String body = "{\"status\":\"pending\",\"correlation_id\":\"cb\",\"review_reasons\":[]}";
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(202, body))) {
      Verdict v = inspect(Fixtures.opts(srv.baseUrl));
      assertEquals("cb", v.correlationId());
    }
  }

  @Test
  void pendingBothEmpty() throws Exception {
    String body = "{\"status\":\"pending\",\"correlation_id\":\"\",\"review_reasons\":[]}";
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(202, body))) {
      ClavenarTransportException e =
          assertThrows(ClavenarTransportException.class, () -> inspect(Fixtures.opts(srv.baseUrl)));
      assertEquals(202, e.status());
      assertTrue(e.getMessage().contains("missing correlation id"));
    }
  }

  @Test
  void unexpectedStatus() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(500, "boom"))) {
      ClavenarTransportException e =
          assertThrows(ClavenarTransportException.class, () -> inspect(Fixtures.opts(srv.baseUrl)));
      assertEquals(500, e.status());
    }
  }

  @Test
  void networkError() throws Exception {
    String url;
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(200, null))) {
      url = srv.baseUrl;
    }
    ClavenarTransportException e =
        assertThrows(ClavenarTransportException.class, () -> inspect(Fixtures.opts(url)));
    assertEquals(0, e.status());
  }

  @Test
  void timeout() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              return TestServer.Response.of(200, null);
            })) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .timeout(Duration.ofMillis(40))
              .build();
      ClavenarTransportException e =
          assertThrows(ClavenarTransportException.class, () -> inspect(opts));
      assertTrue(e.getMessage().contains("timed out"));
    }
  }

  @Test
  void retryThenSuccess() throws Exception {
    AtomicInteger n = new AtomicInteger();
    List<String> bodies = new CopyOnWriteArrayList<>();
    List<String> selectors = new CopyOnWriteArrayList<>();
    List<String> ids = new CopyOnWriteArrayList<>();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              bodies.add(b);
              selectors.add(h.getFirst(Transport.DECISION_CONTRACT_HEADER));
              ids.add(h.getFirst(Transport.IDEMPOTENCY_ID_HEADER));
              return n.incrementAndGet() < 3
                  ? TestServer.Response.of(503, null)
                  : TestServer.Response.of(200, null);
            })) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(3, Duration.ofMillis(1)))
              .build();
      assertEquals(VerdictKind.ALLOW, inspect(opts).kind());
      assertEquals(3, n.get());
      assertEquals(1, bodies.stream().distinct().count());
      assertEquals(List.of(Transport.DECISION_CONTRACT), selectors.stream().distinct().toList());
      assertEquals(1, ids.stream().distinct().count());
    }
  }

  @Test
  void noRetryOn4xx() throws Exception {
    AtomicInteger n = new AtomicInteger();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              n.incrementAndGet();
              return TestServer.Response.of(400, null);
            })) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(3, Duration.ofMillis(1)))
              .build();
      assertThrows(ClavenarTransportException.class, () -> inspect(opts));
      assertEquals(1, n.get());
    }
  }

  @Test
  void maxAttemptsBelowOne() throws Exception {
    ClavenarOptions opts =
        ClavenarOptions.builder("http://127.0.0.1:9")
            .retry(new RetryOptions(0, Duration.ofMillis(1)))
            .build();
    ClavenarTransportException e =
        assertThrows(ClavenarTransportException.class, () -> inspect(opts));
    assertTrue(e.getMessage().contains("maxAttempts"));
  }

  @Test
  void requestEnvelope() throws Exception {
    AtomicReference<String> gotBody = new AtomicReference<>();
    AtomicReference<String> gotAuth = new AtomicReference<>();
    AtomicReference<String> gotCt = new AtomicReference<>();
    AtomicReference<String> gotPath = new AtomicReference<>();
    AtomicReference<String> gotDecision = new AtomicReference<>();
    AtomicReference<String> gotIdempotency = new AtomicReference<>();
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              gotBody.set(b);
              gotPath.set(p);
              gotAuth.set(h.getFirst("Authorization"));
              gotCt.set(h.getFirst("Content-Type"));
              gotDecision.set(h.getFirst(Transport.DECISION_CONTRACT_HEADER));
              gotIdempotency.set(h.getFirst(Transport.IDEMPOTENCY_ID_HEADER));
              return TestServer.Response.of(200, null);
            })) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .token("tok")
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .build();
      inspect(opts);
      assertEquals("/mcp", gotPath.get());
      assertEquals("application/json", gotCt.get());
      assertEquals("Bearer tok", gotAuth.get());
      assertEquals(Transport.DECISION_CONTRACT, gotDecision.get());
      JsonNode env = Json.MAPPER.readTree(gotBody.get());
      assertEquals("2.0", env.get("jsonrpc").asText());
      assertEquals("tools/call", env.get("method").asText());
      assertEquals(gotIdempotency.get(), env.get("id").asText());
      assertDoesNotThrow(() -> java.util.UUID.fromString(env.get("id").asText()));
      assertEquals("delete_user", env.get("params").get("name").asText());
      assertEquals("alice", env.get("params").get("arguments").get("user").asText());
    }
  }

  @Test
  void joinUrl() {
    assertEquals("http://x/mcp", Transport.joinUrl("http://x/", "/mcp"));
    assertEquals("http://x/mcp", Transport.joinUrl("http://x", "mcp"));
    assertEquals("https://gw/clavenar/mcp", Transport.joinUrl("https://gw/clavenar", "/mcp"));
  }

  @Test
  void configErrorOnBadEndpoint() {
    assertThrows(
        ClavenarConfigException.class,
        () -> new ClavenarInspector(ClavenarOptions.builder("").build()));
    assertThrows(
        ClavenarConfigException.class,
        () -> new ClavenarInspector(ClavenarOptions.builder("not-a-url").build()));
  }
}
