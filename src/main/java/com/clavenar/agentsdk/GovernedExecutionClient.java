package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Explicit side-effect-free authorization plus durable registered-executor execution. Authorization
 * is never released as a host-executable model call.
 */
public final class GovernedExecutionClient {
  /** Execution authorization and terminal receipt wire contract. */
  public static final String EXECUTION_CONTRACT = "clavenar.execution/v1";

  /** Application-owned durable intent and receipt-outbox contract. */
  public static final String DURABLE_EXECUTION_CONTRACT = "clavenar.sdk-durable-intent-outbox/v1";

  private final ClavenarOptions decision;
  private final String executorId;
  private final ToolExecutor executor;
  private final DurableExecutionStore store;
  private final ReceiptSigner signer;

  /** Construct a client with its sole executor, durable store, and workload receipt signer. */
  public GovernedExecutionClient(
      ClavenarOptions decision,
      String executorId,
      ToolExecutor executor,
      DurableExecutionStore store,
      ReceiptSigner signer) {
    if (decision == null) {
      throw new ClavenarConfigException("governed execution requires decision options");
    }
    decision.validate();
    if (executorId == null
        || executorId.isBlank()
        || executor == null
        || store == null
        || signer == null) {
      throw new ClavenarConfigException(
          "governed execution requires executor id, executor, durable store, and receipt signer");
    }
    this.decision = decision;
    this.executorId = executorId;
    this.executor = executor;
    this.store = store;
    this.signer = signer;
  }

  /** Serializable exact request whose UUID exists before any network access. */
  public record PreparedToolRequest(String idempotencyId, String name, JsonNode arguments) {}

  /** Exact input released only to the registered executor. */
  public record ToolExecutionRequest(
      String authorizationId, String idempotencyId, String executorId, JsonNode executionPayload) {}

  /** Actual executor result and provider effect identity. */
  public record ExecutionEffect(JsonNode result, String effectId) {}

  /** Actual effect plus its retained terminal receipt. */
  public record GovernedExecutionOutcome(
      JsonNode result, String effectId, String idempotencyId, JsonNode receipt) {}

  /** Application-owned durable intent and completion/outbox transactions. */
  public interface DurableExecutionStore {
    /** Commit the exact signed authorization intent before the effect. */
    void commitIntent(JsonNode intent);

    /** Atomically commit the actual completion and enqueue its receipt. */
    void commitCompletionAndEnqueueReceipt(JsonNode completion);
  }

  /** Sole callback allowed to execute an SDK-governed tool. */
  @FunctionalInterface
  public interface ToolExecutor {
    ExecutionEffect execute(ToolExecutionRequest request);
  }

  /** Workload-key signer for the exact unsigned terminal receipt. */
  @FunctionalInterface
  public interface ReceiptSigner {
    WorkloadSignature sign(JsonNode unsignedReceipt);
  }

  /** Workload signature fields embedded in the terminal receipt. */
  public record WorkloadSignature(String algorithm, String credentialFingerprint, String value) {}

  /** Allocate and validate a new request identity locally. */
  public static PreparedToolRequest prepare(String name, JsonNode arguments) {
    return restore(UUID.randomUUID().toString(), name, arguments);
  }

  /** Restore a previously persisted prepared request without replacing its identity. */
  public static PreparedToolRequest restore(String idempotencyId, String name, JsonNode arguments) {
    PreparedToolRequest prepared = new PreparedToolRequest(idempotencyId, name, arguments);
    validatePrepared(prepared);
    return prepared;
  }

  /** Prepare and execute one exact tool through the governed path. */
  public GovernedExecutionOutcome execute(String name, JsonNode arguments) {
    return executePrepared(prepare(name, arguments));
  }

  /**
   * Authorize side-effect-free, commit intent, invoke the registered executor, and retain the
   * actual completion plus workload-signed receipt.
   */
  public GovernedExecutionOutcome executePrepared(PreparedToolRequest prepared) {
    validatePrepared(prepared);
    ObjectNode body =
        Transport.toolRequest(prepared.name(), prepared.arguments(), prepared.idempotencyId());
    JsonNode signed = Transport.authorize(body, prepared.idempotencyId(), decision);
    JsonNode authorization = validateAuthorization(signed, prepared, body);

    ObjectNode intent = Json.MAPPER.createObjectNode();
    intent.put("contract", DURABLE_EXECUTION_CONTRACT);
    intent.put("stage", "execution.intent");
    copyText(authorization, intent, "authorization_id");
    copyText(authorization, intent, "idempotency_id");
    copyTextAs(authorization, intent, "tenant", "tenant");
    copyTextAs(authorization, intent, "agent_id", "workload_id");
    copyTextAs(authorization, intent, "agent_spiffe", "workload_spiffe");
    copyText(authorization, intent, "payload_sha256");
    intent.put("executor_id", executorId);
    intent.set("authorization", signed.deepCopy());
    store.commitIntent(intent);

    ToolExecutionRequest request =
        new ToolExecutionRequest(
            authorization.get("authorization_id").asText(),
            authorization.get("idempotency_id").asText(),
            executorId,
            authorization.get("execution_payload").deepCopy());
    ExecutionEffect effect = executor.execute(request);
    if (effect == null
        || effect.result() == null
        || effect.effectId() == null
        || effect.effectId().isBlank()) {
      throw new ClavenarConfigException("registered executor returned an invalid effect");
    }
    String resultSha256 = sha256(effect.result());

    ObjectNode unsigned = Json.MAPPER.createObjectNode();
    unsigned.put("contract", EXECUTION_CONTRACT);
    unsigned.put("stage", "execution.completed");
    for (String field :
        List.of(
            "authorization_id",
            "idempotency_id",
            "correlation_id",
            "agent_id",
            "agent_spiffe",
            "tenant",
            "credential_fingerprint",
            "method",
            "payload_sha256")) {
      copyText(authorization, unsigned, field);
    }
    unsigned.set("authorization", signed.deepCopy());
    unsigned.put("result_sha256", resultSha256);
    unsigned.put("effect_id", effect.effectId());
    WorkloadSignature signature = signer.sign(unsigned.deepCopy());
    if (signature == null
        || blank(signature.algorithm())
        || blank(signature.credentialFingerprint())
        || blank(signature.value())) {
      throw new ClavenarConfigException("receipt signer returned an invalid workload signature");
    }
    ObjectNode receipt = unsigned.deepCopy();
    ObjectNode encodedSignature = receipt.putObject("workload_signature");
    encodedSignature.put("algorithm", signature.algorithm());
    encodedSignature.put("credential_fingerprint", signature.credentialFingerprint());
    encodedSignature.put("value", signature.value());

    ObjectNode completion = Json.MAPPER.createObjectNode();
    completion.put("contract", DURABLE_EXECUTION_CONTRACT);
    completion.put("stage", "execution.completed");
    copyText(authorization, completion, "authorization_id");
    copyText(authorization, completion, "idempotency_id");
    completion.put("executor_id", executorId);
    completion.set("actual_result", effect.result().deepCopy());
    completion.put("actual_result_sha256", resultSha256);
    completion.put("effect_id", effect.effectId());
    completion.set("receipt", receipt.deepCopy());
    store.commitCompletionAndEnqueueReceipt(completion);

    return new GovernedExecutionOutcome(
        effect.result().deepCopy(), effect.effectId(), prepared.idempotencyId(), receipt);
  }

  private static JsonNode validateAuthorization(
      JsonNode signed, PreparedToolRequest prepared, JsonNode body) {
    JsonNode authorization = signed.path("authorization");
    if (!authorization.isObject()
        || !EXECUTION_CONTRACT.equals(authorization.path("contract").asText())
        || !"authorization".equals(authorization.path("stage").asText())) {
      throw new ClavenarConfigException("invalid governed execution authorization contract");
    }
    if (!prepared.idempotencyId().equals(authorization.path("idempotency_id").asText())) {
      throw new ClavenarConfigException("authorization changed the idempotency identity");
    }
    requireUuid(authorization.path("authorization_id").asText());
    requireUuid(authorization.path("correlation_id").asText());
    for (String field :
        List.of(
            "authorization_id",
            "idempotency_id",
            "agent_id",
            "agent_spiffe",
            "tenant",
            "credential_fingerprint",
            "method",
            "payload_sha256")) {
      if (!authorization.path(field).isTextual() || authorization.path(field).asText().isEmpty()) {
        throw new ClavenarConfigException("authorization is missing binding: " + field);
      }
    }
    JsonNode modification = authorization.get("modification_diff");
    if ((modification == null || modification.isNull())
        && !authorization.path("execution_payload").equals(body)) {
      throw new ClavenarConfigException("authorization changed an unmodified execution payload");
    }
    return authorization;
  }

  private static void validatePrepared(PreparedToolRequest prepared) {
    if (prepared == null
        || blank(prepared.name())
        || prepared.arguments() == null
        || prepared.arguments().isMissingNode()) {
      throw new ClavenarConfigException("prepared tool name and JSON arguments are required");
    }
    requireUuid(prepared.idempotencyId());
  }

  private static void requireUuid(String value) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value)) {
        throw new IllegalArgumentException("not canonical");
      }
    } catch (IllegalArgumentException error) {
      throw new ClavenarConfigException(
          "idempotency and authorization ids must be canonical UUIDs");
    }
  }

  private static String sha256(JsonNode value) {
    try {
      byte[] canonical =
          Json.MAPPER.writeValueAsString(canonicalize(value)).getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    } catch (Exception error) {
      throw new ClavenarConfigException("result is not JSON serializable: " + error.getMessage());
    }
  }

  private static JsonNode canonicalize(JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = Json.MAPPER.createObjectNode();
      List<String> names = new ArrayList<>();
      value.fieldNames().forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      for (String name : names) {
        sorted.set(name, canonicalize(value.get(name)));
      }
      return sorted;
    }
    if (value.isArray()) {
      ArrayNode ordered = Json.MAPPER.createArrayNode();
      for (JsonNode child : value) {
        ordered.add(canonicalize(child));
      }
      return ordered;
    }
    return value.deepCopy();
  }

  private static void copyText(JsonNode source, ObjectNode target, String field) {
    copyTextAs(source, target, field, field);
  }

  private static void copyTextAs(
      JsonNode source, ObjectNode target, String sourceField, String targetField) {
    target.put(targetField, source.get(sourceField).asText());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
