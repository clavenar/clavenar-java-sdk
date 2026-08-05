package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Explicit side-effect-free authorization plus verified, recoverable registered-executor execution.
 * Authorization is never released as a host-executable model call.
 */
public final class GovernedExecutionClient {
  /** Execution authorization and terminal receipt wire contract. */
  public static final String EXECUTION_CONTRACT = "clavenar.execution/v1";

  /** Application-owned durable intent and receipt-outbox contract. */
  public static final String DURABLE_EXECUTION_CONTRACT = "clavenar.sdk-durable-intent-outbox/v1";

  private static final Duration DEFAULT_FINALIZATION_TIMEOUT = Duration.ofSeconds(30);
  private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
  private static final Set<String> PAYLOAD_FIELDS = Set.of("jsonrpc", "id", "method", "params");
  private static final Set<String> PARAM_FIELDS = Set.of("name", "arguments");

  private final ClavenarOptions decision;
  private final String executorId;
  private final ToolExecutor executor;
  private final DurableExecutionStore store;
  private final ReceiptSigner signer;
  private final AuthorizationVerifier authorizationVerifier;
  private final EffectRecoverer recoverer;
  private final Duration finalizationTimeout;

  /** Construct a client with its executor, durable store, signer, and Identity verifier. */
  public GovernedExecutionClient(
      ClavenarOptions decision,
      String executorId,
      ToolExecutor executor,
      DurableExecutionStore store,
      ReceiptSigner signer,
      AuthorizationVerifier authorizationVerifier) {
    this(
        decision,
        executorId,
        executor,
        store,
        signer,
        authorizationVerifier,
        null,
        DEFAULT_FINALIZATION_TIMEOUT);
  }

  /** Construct a client with optional provider-effect recovery and a finalization deadline. */
  public GovernedExecutionClient(
      ClavenarOptions decision,
      String executorId,
      ToolExecutor executor,
      DurableExecutionStore store,
      ReceiptSigner signer,
      AuthorizationVerifier authorizationVerifier,
      EffectRecoverer recoverer,
      Duration finalizationTimeout) {
    if (decision == null) {
      throw new ClavenarConfigException("governed execution requires decision options");
    }
    decision.validate();
    if (executorId == null
        || executorId.isBlank()
        || executor == null
        || store == null
        || signer == null
        || authorizationVerifier == null) {
      throw new ClavenarConfigException(
          "governed execution requires executor id, executor, recoverable durable store, receipt"
              + " signer, and authorization verifier");
    }
    if (finalizationTimeout == null
        || finalizationTimeout.isZero()
        || finalizationTimeout.isNegative()
        || finalizationTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new ClavenarConfigException(
          "governed execution finalization timeout must be between 1ns and 5m");
    }
    this.decision = decision;
    this.executorId = executorId;
    this.executor = executor;
    this.store = store;
    this.signer = signer;
    this.authorizationVerifier = authorizationVerifier;
    this.recoverer = recoverer;
    this.finalizationTimeout = finalizationTimeout;
  }

  /** Serializable exact request whose UUID exists before any network access. */
  public record PreparedToolRequest(String idempotencyId, String name, JsonNode arguments) {}

  /** Exact input released only to the registered executor. */
  public record ToolExecutionRequest(
      String authorizationId, String idempotencyId, String executorId, JsonNode executionPayload) {}

  /** Actual executor result and provider effect identity. */
  public record ExecutionEffect(JsonNode result, String effectId) {}

  /** Recoverable state for one stable idempotency identity. */
  public record ExecutionState(JsonNode intent, JsonNode completion) {
    /** No durable execution exists yet. */
    public static ExecutionState empty() {
      return new ExecutionState(null, null);
    }
  }

  /** Actual effect plus its retained terminal receipt. */
  public record GovernedExecutionOutcome(
      JsonNode result, String effectId, String idempotencyId, JsonNode receipt) {}

  /** Application-owned durable intent and completion/outbox transactions. */
  public interface DurableExecutionStore {
    /** Load the durable state for the stable idempotency identity. */
    ExecutionState loadExecution(String idempotencyId);

    /** Atomically commit the exact signed authorization intent before the effect. */
    void commitIntent(JsonNode intent);

    /** Atomically commit the actual completion and enqueue its receipt. */
    void commitCompletionAndEnqueueReceipt(JsonNode completion);
  }

  /** Sole callback allowed to execute an SDK-governed tool; it must use the idempotency id. */
  @FunctionalInterface
  public interface ToolExecutor {
    ExecutionEffect execute(ToolExecutionRequest request);
  }

  /** Conclusively reconcile a persisted intent without repeating an ambiguous effect. */
  @FunctionalInterface
  public interface EffectRecoverer {
    /** Return the provider effect when found, or {@code null} while its outcome is ambiguous. */
    ExecutionEffect recover(JsonNode intent);
  }

  /** Cryptographically verify Identity's signature over the exact signed authorization. */
  @FunctionalInterface
  public interface AuthorizationVerifier {
    void verify(JsonNode signedAuthorization);
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
   * Load durable state, authorize and verify when new, commit intent, execute, and atomically
   * retain the actual completion plus workload-signed receipt.
   */
  public GovernedExecutionOutcome executePrepared(PreparedToolRequest prepared) {
    validatePrepared(prepared);
    ObjectNode body =
        Transport.toolRequest(prepared.name(), prepared.arguments(), prepared.idempotencyId());
    ExecutionState state = store.loadExecution(prepared.idempotencyId());
    if (state == null) {
      throw new ClavenarConfigException("durable store returned null execution state");
    }
    if (state.completion() != null) {
      return recoveredCompletion(prepared, body, state);
    }
    if (state.intent() != null) {
      JsonNode authorization = validateStoredIntent(state.intent(), prepared, body);
      if (recoverer == null) {
        throw new ClavenarRecoveryRequired(prepared.idempotencyId());
      }
      ExecutionEffect effect = recoverer.recover(state.intent().deepCopy());
      if (effect == null) {
        throw new ClavenarRecoveryRequired(prepared.idempotencyId());
      }
      return completeExecution(
          state.intent().path("authorization"), authorization, effect, prepared.idempotencyId());
    }

    JsonNode signed = Transport.authorize(body, prepared.idempotencyId(), decision);
    JsonNode authorization = validateAuthorization(signed, prepared, body);
    verifyAuthorization(signed, false);
    ObjectNode intent = executionIntent(signed, authorization);
    store.commitIntent(intent);

    ToolExecutionRequest request =
        new ToolExecutionRequest(
            authorization.get("authorization_id").asText(),
            authorization.get("idempotency_id").asText(),
            executorId,
            authorization.get("execution_payload").deepCopy());
    ExecutionEffect effect = executor.execute(request);
    return completeExecution(signed, authorization, effect, prepared.idempotencyId());
  }

  private ObjectNode executionIntent(JsonNode signed, JsonNode authorization) {
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
    return intent;
  }

  private GovernedExecutionOutcome completeExecution(
      JsonNode signed, JsonNode authorization, ExecutionEffect effect, String idempotencyId) {
    if (effect == null
        || effect.result() == null
        || effect.result().isMissingNode()
        || blank(effect.effectId())) {
      throw new ClavenarConfigException("registered executor returned an invalid effect");
    }
    ExecutionEffect stableEffect =
        new ExecutionEffect(effect.result().deepCopy(), effect.effectId());
    String resultSha256 = sha256(stableEffect.result());
    ObjectNode unsigned = unsignedReceipt(signed, authorization, stableEffect, resultSha256);
    WorkloadSignature signature =
        runBounded(() -> signer.sign(unsigned.deepCopy()), "receipt signing");
    if (signature == null
        || blank(signature.algorithm())
        || blank(signature.credentialFingerprint())
        || blank(signature.value())) {
      throw new ClavenarConfigException("receipt signer returned an invalid workload signature");
    }
    if (!authorization
        .path("credential_fingerprint")
        .asText()
        .equals(signature.credentialFingerprint())) {
      throw new ClavenarConfigException(
          "receipt signer credential does not match the authorization");
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
    completion.set("actual_result", stableEffect.result().deepCopy());
    completion.put("actual_result_sha256", resultSha256);
    completion.put("effect_id", stableEffect.effectId());
    completion.set("receipt", receipt.deepCopy());
    runBounded(
        () -> {
          store.commitCompletionAndEnqueueReceipt(completion.deepCopy());
          return null;
        },
        "durable completion");

    return new GovernedExecutionOutcome(
        stableEffect.result().deepCopy(), stableEffect.effectId(), idempotencyId, receipt);
  }

  private static ObjectNode unsignedReceipt(
      JsonNode signed, JsonNode authorization, ExecutionEffect effect, String resultSha256) {
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
    return unsigned;
  }

  private JsonNode validateStoredIntent(
      JsonNode intent, PreparedToolRequest prepared, JsonNode body) {
    if (!intent.isObject()
        || !DURABLE_EXECUTION_CONTRACT.equals(intent.path("contract").asText())
        || !"execution.intent".equals(intent.path("stage").asText())
        || !prepared.idempotencyId().equals(intent.path("idempotency_id").asText())
        || !executorId.equals(intent.path("executor_id").asText())
        || !intent.path("authorization").isObject()) {
      throw new ClavenarConfigException(
          "stored execution intent does not match the prepared request");
    }
    JsonNode signed = intent.path("authorization");
    JsonNode authorization = validateAuthorization(signed, prepared, body);
    if (!intent.path("authorization_id").equals(authorization.path("authorization_id"))
        || !intent.path("tenant").equals(authorization.path("tenant"))
        || !intent.path("workload_id").equals(authorization.path("agent_id"))
        || !intent.path("workload_spiffe").equals(authorization.path("agent_spiffe"))
        || !intent.path("payload_sha256").equals(authorization.path("payload_sha256"))) {
      throw new ClavenarConfigException("stored execution intent changed an authorization binding");
    }
    verifyAuthorization(signed, true);
    return authorization;
  }

  private GovernedExecutionOutcome recoveredCompletion(
      PreparedToolRequest prepared, JsonNode body, ExecutionState state) {
    if (state.intent() == null) {
      throw new ClavenarConfigException("durable completion is missing its execution intent");
    }
    JsonNode authorization = validateStoredIntent(state.intent(), prepared, body);
    JsonNode completion = state.completion();
    if (completion == null
        || !completion.isObject()
        || !DURABLE_EXECUTION_CONTRACT.equals(completion.path("contract").asText())
        || !"execution.completed".equals(completion.path("stage").asText())
        || !completion.path("authorization_id").equals(authorization.path("authorization_id"))
        || !prepared.idempotencyId().equals(completion.path("idempotency_id").asText())
        || !executorId.equals(completion.path("executor_id").asText())
        || completion.path("effect_id").asText().isBlank()
        || !completion.path("receipt").isObject()) {
      throw new ClavenarConfigException("stored execution completion is invalid");
    }
    String resultSha256 = sha256(completion.path("actual_result"));
    JsonNode receipt = completion.path("receipt");
    JsonNode signature = receipt.path("workload_signature");
    if (!resultSha256.equals(completion.path("actual_result_sha256").asText())
        || !resultSha256.equals(receipt.path("result_sha256").asText())
        || !receipt.path("authorization_id").equals(authorization.path("authorization_id"))
        || !receipt.path("idempotency_id").equals(completion.path("idempotency_id"))
        || !receipt.path("effect_id").equals(completion.path("effect_id"))
        || !EXECUTION_CONTRACT.equals(receipt.path("contract").asText())
        || !"execution.completed".equals(receipt.path("stage").asText())
        || !receipt.path("correlation_id").equals(authorization.path("correlation_id"))
        || !receipt.path("agent_id").equals(authorization.path("agent_id"))
        || !receipt.path("agent_spiffe").equals(authorization.path("agent_spiffe"))
        || !receipt.path("tenant").equals(authorization.path("tenant"))
        || !receipt
            .path("credential_fingerprint")
            .equals(authorization.path("credential_fingerprint"))
        || !receipt.path("method").equals(authorization.path("method"))
        || !receipt.path("payload_sha256").equals(authorization.path("payload_sha256"))
        || !receipt.path("authorization").equals(state.intent().path("authorization"))
        || !signature
            .path("credential_fingerprint")
            .equals(authorization.path("credential_fingerprint"))
        || signature.path("algorithm").asText().isBlank()
        || signature.path("value").asText().isBlank()) {
      throw new ClavenarConfigException("stored execution completion failed integrity validation");
    }
    return new GovernedExecutionOutcome(
        completion.path("actual_result").deepCopy(),
        completion.path("effect_id").asText(),
        prepared.idempotencyId(),
        receipt.deepCopy());
  }

  private void verifyAuthorization(JsonNode signed, boolean stored) {
    try {
      authorizationVerifier.verify(signed.deepCopy());
    } catch (RuntimeException error) {
      String prefix = stored ? "stored authorization" : "authorization";
      throw new ClavenarConfigException(
          prefix + " signature verification failed: " + error.getMessage());
    }
  }

  private static JsonNode validateAuthorization(
      JsonNode signed, PreparedToolRequest prepared, JsonNode body) {
    if (signed == null
        || !signed.isObject()
        || !signed.path("identity_signature").isObject()
        || signed.path("identity_signature").isEmpty()) {
      throw new ClavenarConfigException("authorization is missing a valid identity signature");
    }
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
        List.of("agent_id", "agent_spiffe", "tenant", "credential_fingerprint", "brain_version")) {
      if (!authorization.path(field).isTextual() || authorization.path(field).asText().isBlank()) {
        throw new ClavenarConfigException("authorization is missing binding: " + field);
      }
    }
    if (!validSha256(authorization.path("payload_sha256").asText())
        || !validSha256(authorization.path("brain_evidence_sha256").asText())) {
      throw new ClavenarConfigException("authorization is missing an execution digest binding");
    }
    if (!authorization.path("decision_principal").isObject()
        || !authorization.path("policy_bundle").isObject()) {
      throw new ClavenarConfigException("authorization contains invalid decision evidence");
    }
    if (!"tools/call".equals(authorization.path("method").asText())
        || !prepared.name().equals(authorization.path("tool_name").asText())) {
      throw new ClavenarConfigException("authorization changed the tool binding");
    }
    JsonNode payload = authorization.path("execution_payload");
    JsonNode params = payload.path("params");
    if (!payload.isObject()
        || !fieldNames(payload).equals(PAYLOAD_FIELDS)
        || !"2.0".equals(payload.path("jsonrpc").asText())
        || !"tools/call".equals(payload.path("method").asText())
        || !prepared.idempotencyId().equals(payload.path("id").asText())
        || !params.isObject()
        || !fieldNames(params).equals(PARAM_FIELDS)
        || !prepared.name().equals(params.path("name").asText())) {
      throw new ClavenarConfigException(
          "authorization execution payload changed a protected request binding");
    }
    if (!authorization.path("payload_sha256").asText().equals(sha256(payload))) {
      throw new ClavenarConfigException(
          "authorization payload digest does not match execution payload");
    }
    JsonNode modification = authorization.get("modification_diff");
    if ((modification == null || modification.isNull())
        && !canonicalJson(payload).equals(canonicalJson(body))) {
      throw new ClavenarConfigException("authorization changed an unmodified execution payload");
    }
    return authorization;
  }

  private static Set<String> fieldNames(JsonNode value) {
    Set<String> fields = new HashSet<>();
    value.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private static void validatePrepared(PreparedToolRequest prepared) {
    if (prepared == null
        || blank(prepared.name())
        || prepared.arguments() == null
        || prepared.arguments().isMissingNode()) {
      throw new ClavenarConfigException("prepared tool name and JSON arguments are required");
    }
    requireUuid(prepared.idempotencyId());
    canonicalJson(prepared.arguments());
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

  private static boolean validSha256(String value) {
    return value != null && value.matches("sha256:[0-9a-f]{64}");
  }

  private static String sha256(JsonNode value) {
    try {
      byte[] canonical = canonicalJson(value).getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  /** Sorted UTF-16 object keys with the shared finite, safely representable number subset. */
  private static String canonicalJson(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isBinary() || value.isPojo()) {
      throw new ClavenarConfigException("value is not a supported JSON value");
    }
    if (value.isObject()) {
      List<String> names = new ArrayList<>();
      value.fieldNames().forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      List<String> entries = new ArrayList<>();
      for (String name : names) {
        entries.add(encodeString(name) + ":" + canonicalJson(value.get(name)));
      }
      return "{" + String.join(",", entries) + "}";
    }
    if (value.isArray()) {
      List<String> entries = new ArrayList<>();
      value.forEach(child -> entries.add(canonicalJson(child)));
      return "[" + String.join(",", entries) + "]";
    }
    if (value.isTextual()) {
      return encodeString(value.asText());
    }
    if (value.isBoolean()) {
      return value.asBoolean() ? "true" : "false";
    }
    if (value.isNull()) {
      return "null";
    }
    if (value.isIntegralNumber()) {
      BigInteger integer = value.bigIntegerValue();
      if (integer.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
        throw new ClavenarConfigException("JSON integers must be safely representable");
      }
      return integer.toString();
    }
    if (value.isFloatingPointNumber()) {
      double number = value.doubleValue();
      if (!Double.isFinite(number)) {
        throw new ClavenarConfigException("JSON numbers must be finite");
      }
      return ecmaNumber(number);
    }
    throw new ClavenarConfigException("value is not a supported JSON value");
  }

  private static String encodeString(String value) {
    try {
      return Json.MAPPER.writeValueAsString(value);
    } catch (Exception error) {
      throw new ClavenarConfigException("value is not JSON serializable: " + error.getMessage());
    }
  }

  private static String ecmaNumber(double value) {
    if (value == 0.0d) {
      return "0";
    }
    String raw = Double.toString(value).toLowerCase();
    if (!raw.contains("e")) {
      return raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
    }
    String[] parts = raw.split("e", 2);
    String coefficient = parts[0];
    int exponent = Integer.parseInt(parts[1]);
    String sign = "";
    if (coefficient.startsWith("-")) {
      sign = "-";
      coefficient = coefficient.substring(1);
    }
    String digits = coefficient.replace(".", "");
    int decimalPosition = 1 + exponent;
    double absolute = Math.abs(value);
    if (absolute >= 1e-6 && absolute < 1e21) {
      if (decimalPosition <= 0) {
        return sign + "0." + "0".repeat(-decimalPosition) + digits;
      }
      if (decimalPosition >= digits.length()) {
        return sign + digits + "0".repeat(decimalPosition - digits.length());
      }
      return sign + digits.substring(0, decimalPosition) + "." + digits.substring(decimalPosition);
    }
    String normalized = digits.substring(0, 1);
    String tail = digits.substring(1).replaceFirst("0+$", "");
    if (!tail.isEmpty()) {
      normalized += "." + tail;
    }
    return sign + normalized + "e" + (exponent >= 0 ? "+" : "-") + Math.abs(exponent);
  }

  private <T> T runBounded(Supplier<T> operation, String name) {
    FutureTask<T> task = new FutureTask<>(operation::get);
    Thread thread = new Thread(task, "clavenar-governed-finalization");
    thread.setDaemon(true);
    thread.start();
    try {
      return task.get(finalizationTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException error) {
      task.cancel(true);
      throw new ClavenarTransportException(name + " timed out after " + finalizationTimeout, error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ClavenarTransportException(name + " was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new ClavenarTransportException(name + " failed", cause);
    }
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
