package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clavenar.agentsdk.GovernedExecutionClient.ExecutionEffect;
import com.clavenar.agentsdk.GovernedExecutionClient.ExecutionState;
import com.clavenar.agentsdk.GovernedExecutionClient.PreparedToolRequest;
import com.clavenar.agentsdk.GovernedExecutionClient.WorkloadSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class GovernedExecutionClientTest {
  private static final String IDEMPOTENCY_ID = "cfcc8767-4c73-41cc-8ece-b855863924c4";

  @Test
  void commitsIntentBeforeOneEffectAndReturnsActualResult() throws Exception {
    AtomicReference<String> decisionHeader = new AtomicReference<>();
    AtomicReference<String> idHeader = new AtomicReference<>();
    try (TestServer server =
        new TestServer(
            (method, path, body, headers) -> {
              decisionHeader.set(headers.getFirst(Transport.DECISION_CONTRACT_HEADER));
              idHeader.set(headers.getFirst(Transport.IDEMPOTENCY_ID_HEADER));
              return TestServer.Response.of(200, authorization(body));
            })) {
      List<String> order = new ArrayList<>();
      AtomicReference<JsonNode> completion = new AtomicReference<>();
      AtomicReference<JsonNode> intentState = new AtomicReference<>();
      GovernedExecutionClient client =
          new GovernedExecutionClient(
              Fixtures.opts(server.baseUrl),
              "payments-provider",
              request -> {
                order.add("effect");
                assertEquals(IDEMPOTENCY_ID, request.idempotencyId());
                return new ExecutionEffect(
                    Json.MAPPER.createObjectNode().put("ok", true), "provider-operation-123");
              },
              new GovernedExecutionClient.DurableExecutionStore() {
                @Override
                public ExecutionState loadExecution(String idempotencyId) {
                  return new ExecutionState(intentState.get(), completion.get());
                }

                @Override
                public void commitIntent(JsonNode intent) {
                  order.add("intent");
                  assertEquals("payments-provider", intent.path("executor_id").asText());
                  intentState.set(intent.deepCopy());
                }

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode value) {
                  order.add("completion");
                  completion.set(value);
                }
              },
              receipt -> {
                ((ObjectNode) receipt).put("authorization_id", "mutated-by-signer");
                return new WorkloadSignature("ES256", "sha256:" + "1".repeat(64), "signed");
              },
              signed ->
                  ((ObjectNode) signed.path("authorization"))
                      .put("tool_name", "mutated-by-verifier"));
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      var outcome = client.executePrepared(prepared);

      assertEquals(List.of("intent", "effect", "completion"), order);
      assertEquals(true, outcome.result().path("ok").asBoolean());
      assertEquals("provider-operation-123", outcome.effectId());
      assertEquals(
          "354c33ed-e5d3-4af7-a1b8-b009d50b0bc5",
          outcome.receipt().path("authorization_id").asText());
      assertEquals(
          "payments.transfer",
          outcome.receipt().path("authorization").path("authorization").path("tool_name").asText());
      assertEquals(Transport.DECISION_CONTRACT, decisionHeader.get());
      assertEquals(IDEMPOTENCY_ID, idHeader.get());
      assertEquals(
          "sha256:4062edaf750fb8074e7e83e0c9028c94e32468a8b6f1614774328ef045150f93",
          completion.get().path("actual_result_sha256").asText());
    }
  }

  @Test
  void intentFailureInvokesNoExecutor() throws Exception {
    try (TestServer server =
        new TestServer(
            (method, path, body, headers) -> TestServer.Response.of(200, authorization(body)))) {
      AtomicBoolean executed = new AtomicBoolean();
      GovernedExecutionClient client =
          new GovernedExecutionClient(
              Fixtures.opts(server.baseUrl),
              "payments-provider",
              request -> {
                executed.set(true);
                return new ExecutionEffect(Json.MAPPER.createObjectNode(), "unexpected");
              },
              new GovernedExecutionClient.DurableExecutionStore() {
                @Override
                public ExecutionState loadExecution(String idempotencyId) {
                  return ExecutionState.empty();
                }

                @Override
                public void commitIntent(JsonNode intent) {
                  throw new IllegalStateException("store unavailable");
                }

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode completion) {}
              },
              receipt -> new WorkloadSignature("ES256", "fingerprint", "signed"),
              signed -> {});
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      assertThrows(IllegalStateException.class, () -> client.executePrepared(prepared));
      assertFalse(executed.get());
    }
  }

  @Test
  void executorFailureIsNeverRetried() throws Exception {
    AtomicInteger decisions = new AtomicInteger();
    AtomicInteger effects = new AtomicInteger();
    try (TestServer server =
        new TestServer(
            (method, path, body, headers) -> {
              decisions.incrementAndGet();
              return TestServer.Response.of(200, authorization(body));
            })) {
      ClavenarOptions options =
          ClavenarOptions.builder(server.baseUrl)
              .retry(new RetryOptions(3, java.time.Duration.ofMillis(1)))
              .build();
      GovernedExecutionClient client =
          new GovernedExecutionClient(
              options,
              "payments-provider",
              request -> {
                effects.incrementAndGet();
                throw new IllegalStateException("provider response lost");
              },
              new GovernedExecutionClient.DurableExecutionStore() {
                @Override
                public ExecutionState loadExecution(String idempotencyId) {
                  return ExecutionState.empty();
                }

                @Override
                public void commitIntent(JsonNode intent) {}

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode completion) {}
              },
              receipt -> {
                throw new AssertionError("signer must not run");
              },
              signed -> {});
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      assertThrows(IllegalStateException.class, () -> client.executePrepared(prepared));
      assertEquals(1, decisions.get());
      assertEquals(1, effects.get());
    }
  }

  @Test
  void rejectsUnverifiedAuthorizationBeforeIntent() throws Exception {
    try (TestServer server =
        new TestServer(
            (method, path, body, headers) -> TestServer.Response.of(200, authorization(body)))) {
      AtomicBoolean executed = new AtomicBoolean();
      AtomicBoolean committed = new AtomicBoolean();
      GovernedExecutionClient client =
          new GovernedExecutionClient(
              Fixtures.opts(server.baseUrl),
              "payments-provider",
              request -> {
                executed.set(true);
                return new ExecutionEffect(Json.MAPPER.createObjectNode(), "unexpected");
              },
              new GovernedExecutionClient.DurableExecutionStore() {
                @Override
                public ExecutionState loadExecution(String idempotencyId) {
                  return ExecutionState.empty();
                }

                @Override
                public void commitIntent(JsonNode intent) {
                  committed.set(true);
                }

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode completion) {}
              },
              receipt -> new WorkloadSignature("ES256", "fingerprint", "signed"),
              signed -> {
                throw new IllegalStateException("unknown identity key");
              });
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      ClavenarConfigException error =
          assertThrows(ClavenarConfigException.class, () -> client.executePrepared(prepared));
      assertEquals(true, error.getMessage().contains("signature verification failed"));
      assertFalse(committed.get());
      assertFalse(executed.get());
    }
  }

  @Test
  void persistedIntentRequiresReconciliationInsteadOfReplay() throws Exception {
    PreparedToolRequest prepared =
        GovernedExecutionClient.restore(
            IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));
    JsonNode signed =
        Json.MAPPER.readTree(
            authorization(
                Json.MAPPER.writeValueAsString(
                    Transport.toolRequest(
                        prepared.name(), prepared.arguments(), prepared.idempotencyId()))));
    JsonNode auth = signed.path("authorization");
    ObjectNode intent = Json.MAPPER.createObjectNode();
    intent.put("contract", GovernedExecutionClient.DURABLE_EXECUTION_CONTRACT);
    intent.put("stage", "execution.intent");
    intent.put("authorization_id", auth.path("authorization_id").asText());
    intent.put("idempotency_id", auth.path("idempotency_id").asText());
    intent.put("tenant", auth.path("tenant").asText());
    intent.put("workload_id", auth.path("agent_id").asText());
    intent.put("workload_spiffe", auth.path("agent_spiffe").asText());
    intent.put("payload_sha256", auth.path("payload_sha256").asText());
    intent.put("executor_id", "payments-provider");
    intent.set("authorization", signed);
    AtomicBoolean executed = new AtomicBoolean();
    GovernedExecutionClient client =
        new GovernedExecutionClient(
            Fixtures.opts("https://gateway.invalid"),
            "payments-provider",
            request -> {
              executed.set(true);
              return new ExecutionEffect(Json.MAPPER.createObjectNode(), "unexpected");
            },
            new GovernedExecutionClient.DurableExecutionStore() {
              @Override
              public ExecutionState loadExecution(String idempotencyId) {
                return new ExecutionState(intent, null);
              }

              @Override
              public void commitIntent(JsonNode value) {}

              @Override
              public void commitCompletionAndEnqueueReceipt(JsonNode completion) {}
            },
            receipt -> new WorkloadSignature("ES256", "sha256:" + "1".repeat(64), "signed"),
            value -> {});

    RuntimeException error =
        assertThrows(RuntimeException.class, () -> client.executePrepared(prepared));
    assertInstanceOf(ClavenarRecoveryRequired.class, error);
    assertFalse(executed.get());
  }

  private static String authorization(String requestBody) {
    try {
      ObjectNode authorization = Json.MAPPER.createObjectNode();
      authorization.put("contract", GovernedExecutionClient.EXECUTION_CONTRACT);
      authorization.put("stage", "authorization");
      authorization.put("authorization_id", "354c33ed-e5d3-4af7-a1b8-b009d50b0bc5");
      authorization.put("idempotency_id", IDEMPOTENCY_ID);
      authorization.put("correlation_id", "c1a28e4c-a17d-5b3d-884b-e5b627f762c2");
      authorization.put("agent_id", "payments-agent");
      authorization.put(
          "agent_spiffe", "spiffe://clavenar.local/tenant/acme/agent/payments-agent/instance/one");
      authorization.put("tenant", "acme");
      authorization.put("credential_fingerprint", "sha256:" + "1".repeat(64));
      authorization.put("method", "tools/call");
      authorization.put("tool_name", "payments.transfer");
      authorization.set("execution_payload", Json.MAPPER.readTree(requestBody));
      authorization.put(
          "payload_sha256",
          "sha256:269123e546c75ec2df26ce4a52baeab92e58afdfabcb111c3e9069a37f78f1c5");
      authorization.putObject("decision_principal").put("subject", "system:policy-brain");
      authorization.putNull("modification_diff");
      authorization.putObject("policy_bundle").put("schema_version", 1);
      authorization.put("brain_version", "brain-fixture");
      authorization.put("brain_evidence_sha256", "sha256:" + "3".repeat(64));
      ObjectNode signed = Json.MAPPER.createObjectNode();
      signed.set("authorization", authorization);
      signed.putObject("identity_signature").put("algorithm", "Ed25519");
      return Json.MAPPER.writeValueAsString(signed);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
