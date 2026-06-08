package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InspectorTest {

  private static NormalizedToolCall call(String id, String name) {
    return NormalizedToolCall.fromJsonArguments(id, name, "{}");
  }

  @Test
  void emptyIsNoop() {
    new ClavenarInspector(Fixtures.opts("http://127.0.0.1:9")).inspectAll(List.of());
  }

  @Test
  void orderedFirstDeny() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) -> {
              if ("slow_deny".equals(Fixtures.toolName(b))) {
                try {
                  Thread.sleep(40);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              }
              return TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}");
            })) {
      ClavenarInspector inspector = new ClavenarInspector(Fixtures.opts(srv.baseUrl));
      ClavenarDenied d =
          assertThrows(
              ClavenarDenied.class,
              () -> inspector.inspectAll(List.of(call("1", "slow_deny"), call("2", "fast_deny"))));
      assertEquals("slow_deny", d.toolName());
    }
  }

  @Test
  void enforceTransportErrorNoPolicyCallback() throws Exception {
    AtomicBoolean policyFired = new AtomicBoolean(false);
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(500, null))) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .onPolicyError((e, ctx) -> policyFired.set(true))
              .build();
      assertThrows(
          ClavenarTransportException.class,
          () -> new ClavenarInspector(opts).inspectAll(List.of(Fixtures.sampleCall())));
      assertFalse(policyFired.get(), "onPolicyError must not fire in enforce mode");
    }
  }

  @Test
  void observePassesThroughDeny() throws Exception {
    List<VerdictKind> kinds = new ArrayList<>();
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
      new ClavenarInspector(opts).inspectAll(List.of(Fixtures.sampleCall()));
      assertEquals(List.of(VerdictKind.DENY), kinds);
    }
  }

  @Test
  void observeTransportTreatedAllow() throws Exception {
    AtomicInteger polCalls = new AtomicInteger();
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(500, null))) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .observe()
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .onPolicyError((e, ctx) -> polCalls.incrementAndGet())
              .build();
      new ClavenarInspector(opts).inspectAll(List.of(Fixtures.sampleCall()));
      assertEquals(1, polCalls.get());
    }
  }

  @Test
  void pendingEnforce() throws Exception {
    String body =
        "{\"status\":\"pending\",\"correlation_id\":\"cp\",\"review_reasons\":[\"needs review\"]}";
    try (TestServer srv =
        new TestServer((m, p, b, h) -> TestServer.Response.of(202, body).corr("cp"))) {
      ClavenarPending pend =
          assertThrows(
              ClavenarPending.class,
              () ->
                  new ClavenarInspector(Fixtures.opts(srv.baseUrl))
                      .inspectAll(List.of(Fixtures.sampleCall())));
      assertEquals("cp", pend.correlationId());
      assertEquals("delete_user", pend.toolName());
    }
  }

  @Test
  void onVerdictErrorPropagates() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(200, null))) {
      ClavenarOptions opts =
          ClavenarOptions.builder(srv.baseUrl)
              .retry(new RetryOptions(1, Duration.ofMillis(1)))
              .onVerdict(
                  (v, ctx) -> {
                    throw new IllegalStateException("stop");
                  })
              .build();
      assertThrows(
          IllegalStateException.class,
          () -> new ClavenarInspector(opts).inspectAll(List.of(Fixtures.sampleCall())));
    }
  }

  @Test
  void enforceConvenience() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}"))) {
      ClavenarInspector inspector = new ClavenarInspector(Fixtures.opts(srv.baseUrl));
      assertThrows(
          ClavenarDenied.class, () -> inspector.enforce("delete_user", "call_1", "{\"u\":1}"));
      assertTrue(true);
    }
  }
}
