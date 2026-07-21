package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clavenar.agentsdk.GovernedExecutionClient.ExecutionEffect;
import com.clavenar.agentsdk.GovernedExecutionClient.PreparedToolRequest;
import com.clavenar.agentsdk.GovernedExecutionClient.WorkloadSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
                public void commitIntent(JsonNode intent) {
                  order.add("intent");
                  assertEquals("payments-provider", intent.path("executor_id").asText());
                }

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode value) {
                  order.add("completion");
                  completion.set(value);
                }
              },
              receipt -> new WorkloadSignature("ES256", "sha256:" + "1".repeat(64), "signed"));
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      var outcome = client.executePrepared(prepared);

      assertEquals(List.of("intent", "effect", "completion"), order);
      assertEquals(true, outcome.result().path("ok").asBoolean());
      assertEquals("provider-operation-123", outcome.effectId());
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
                public void commitIntent(JsonNode intent) {
                  throw new IllegalStateException("store unavailable");
                }

                @Override
                public void commitCompletionAndEnqueueReceipt(JsonNode completion) {}
              },
              receipt -> new WorkloadSignature("ES256", "fingerprint", "signed"));
      PreparedToolRequest prepared =
          GovernedExecutionClient.restore(
              IDEMPOTENCY_ID, "payments.transfer", Json.MAPPER.readTree("{\"amount\":100}"));

      assertThrows(IllegalStateException.class, () -> client.executePrepared(prepared));
      assertFalse(executed.get());
    }
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
      authorization.put("payload_sha256", "sha256:" + "2".repeat(64));
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
