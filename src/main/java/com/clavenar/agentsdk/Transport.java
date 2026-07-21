package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Wire transport: POST /mcp inspection with retry, and GET /pending/{id} polling. */
final class Transport {
  private static final String CORRELATION_HEADER = "X-Clavenar-Correlation-Id";
  static final String DECISION_CONTRACT = "clavenar.decision/v1";
  static final String DECISION_CONTRACT_HEADER = "X-Clavenar-Decision-Contract";
  static final String IDEMPOTENCY_ID_HEADER = "X-Clavenar-Idempotency-Id";

  private Transport() {}

  static Verdict inspect(NormalizedToolCall call, ClavenarOptions o) {
    String idempotencyId = UUID.randomUUID().toString();
    ObjectNode root = toolRequest(call.name(), call.input(), idempotencyId);
    return inspectDecision(root, idempotencyId, o);
  }

  static Verdict inspectBatch(List<NormalizedToolCall> calls, ClavenarOptions o) {
    if (calls == null || calls.isEmpty() || calls.size() > 128) {
      throw new ClavenarConfigException("atomic decision batch must contain 1..128 calls");
    }
    java.util.HashSet<String> ids = new java.util.HashSet<>();
    String idempotencyId = UUID.randomUUID().toString();
    ObjectNode root = Json.MAPPER.createObjectNode();
    root.put("jsonrpc", "2.0");
    root.put("id", idempotencyId);
    root.put("method", "clavenar/tools.batch");
    ObjectNode params = root.putObject("params");
    params.put("name", "clavenar.atomic-batch");
    ObjectNode arguments = params.putObject("arguments");
    arguments.put("contract", "clavenar.atomic-tool-call-batch/v1");
    com.fasterxml.jackson.databind.node.ArrayNode encodedCalls = arguments.putArray("calls");
    for (NormalizedToolCall call : calls) {
      if (call.id() == null
          || call.id().isEmpty()
          || call.name() == null
          || call.name().isEmpty()
          || !ids.add(call.id())) {
        throw new ClavenarConfigException(
            "atomic decision calls require unique non-empty ids and names");
      }
      ObjectNode encoded = encodedCalls.addObject();
      encoded.put("id", call.id());
      encoded.put("name", call.name());
      encoded.set("arguments", call.input());
    }
    return inspectDecision(root, idempotencyId, o);
  }

  private static Verdict inspectDecision(ObjectNode body, String idempotencyId, ClavenarOptions o) {
    RetryOptions r = o.retry();
    if (r.maxAttempts() < 1) {
      throw new ClavenarTransportException(
          "clavenar: retry.maxAttempts must be >= 1, got " + r.maxAttempts());
    }
    ClavenarTransportException last = null;
    for (int attempt = 0; attempt < r.maxAttempts(); attempt++) {
      try {
        return inspectOnce(body, idempotencyId, o);
      } catch (ClavenarTransportException e) {
        last = e;
        if (!isRetriable(e) || attempt == r.maxAttempts() - 1) {
          throw e;
        }
        sleep(backoffMillis(r.baseDelay(), attempt));
      }
    }
    throw last; // unreachable: the loop returns or throws on the final attempt.
  }

  private static Verdict inspectOnce(ObjectNode root, String idempotencyId, ClavenarOptions o) {
    String body = encode(root, "inspect");

    HttpRequest.Builder rb = decisionRequest(body, idempotencyId, o);
    HttpResponse<String> resp = send(o.httpClient(), rb.build(), o.timeout(), "inspect");
    String corr = resp.headers().firstValue(CORRELATION_HEADER).orElse(null);
    int status = resp.statusCode();
    switch (status) {
      case 200:
        return new Verdict(VerdictKind.ALLOW, corr, null, null, null, null);
      case 403:
        return parseDeny(resp.body(), corr);
      case 202:
        return parsePending(resp.body(), corr);
      case 429:
        return parseRateLimit(resp.body(), corr);
      default:
        String text = resp.body() == null ? "" : resp.body().strip();
        String msg = "clavenar inspect: unexpected status " + status;
        if (!text.isEmpty()) {
          msg += ": " + text;
        }
        throw new ClavenarTransportException(msg, status);
    }
  }

  static JsonNode authorize(ObjectNode body, String idempotencyId, ClavenarOptions o) {
    RetryOptions retry = o.retry();
    ClavenarTransportException last = null;
    String encoded = encode(body, "authorization");
    for (int attempt = 0; attempt < retry.maxAttempts(); attempt++) {
      HttpResponse<String> response;
      try {
        response =
            send(
                o.httpClient(),
                decisionRequest(encoded, idempotencyId, o).build(),
                o.timeout(),
                "authorization");
      } catch (ClavenarTransportException error) {
        last = error;
        if (!isRetriable(error) || attempt == retry.maxAttempts() - 1) {
          throw error;
        }
        sleep(backoffMillis(retry.baseDelay(), attempt));
        continue;
      }
      if (response.statusCode() == 200) {
        try {
          JsonNode parsed = Json.MAPPER.readTree(response.body());
          if (parsed == null || !parsed.isObject()) {
            throw new ClavenarTransportException(
                "clavenar authorization returned a non-object", 200);
          }
          return parsed;
        } catch (ClavenarTransportException e) {
          throw e;
        } catch (Exception e) {
          throw new ClavenarTransportException(
              "clavenar authorization returned invalid JSON: " + e.getMessage(), 200);
        }
      }
      String text = response.body() == null ? "" : response.body().strip();
      last =
          new ClavenarTransportException(
              "clavenar authorization: unexpected status "
                  + response.statusCode()
                  + (text.isEmpty() ? "" : ": " + text),
              response.statusCode());
      if (!isRetriable(last) || attempt == retry.maxAttempts() - 1) {
        throw last;
      }
      sleep(backoffMillis(retry.baseDelay(), attempt));
    }
    throw last;
  }

  static ObjectNode toolRequest(String name, JsonNode input, String idempotencyId) {
    ObjectNode root = Json.MAPPER.createObjectNode();
    root.put("jsonrpc", "2.0");
    root.put("method", "tools/call");
    ObjectNode params = root.putObject("params");
    params.put("name", name);
    params.set("arguments", input);
    root.put("id", idempotencyId);
    return root;
  }

  private static String encode(JsonNode body, String operation) {
    try {
      return Json.MAPPER.writeValueAsString(body);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar " + operation + ": failed to encode request: " + e.getMessage());
    }
  }

  private static HttpRequest.Builder decisionRequest(
      String body, String idempotencyId, ClavenarOptions o) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(joinUrl(o.endpoint(), "/mcp")))
            .timeout(o.timeout())
            .header("Content-Type", "application/json")
            .header(DECISION_CONTRACT_HEADER, DECISION_CONTRACT)
            .header(IDEMPOTENCY_ID_HEADER, idempotencyId)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (o.token() != null && !o.token().isEmpty()) {
      builder.header("Authorization", "Bearer " + o.token());
    }
    return builder;
  }

  static ClavenarPendingView pollPendingOnce(String correlationId, ClavenarOptions o) {
    String path =
        "/pending/" + URLEncoder.encode(correlationId, StandardCharsets.UTF_8).replace("+", "%20");
    HttpRequest.Builder rb =
        HttpRequest.newBuilder(URI.create(joinUrl(o.endpoint(), path))).timeout(o.timeout()).GET();
    if (o.token() != null && !o.token().isEmpty()) {
      rb.header("Authorization", "Bearer " + o.token());
    }

    HttpResponse<String> resp = send(o.httpClient(), rb.build(), o.timeout(), "poll");
    int status = resp.statusCode();
    if (status != 200) {
      String text = resp.body() == null ? "" : resp.body().strip();
      String msg = "clavenar poll: unexpected status " + status;
      if (!text.isEmpty()) {
        msg += ": " + text;
      }
      throw new ClavenarTransportException(msg, status);
    }

    ClavenarPendingView view;
    try {
      view = Json.MAPPER.readValue(resp.body(), ClavenarPendingView.class);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar poll with unparseable body: " + e.getMessage(), 200);
    }
    String decision = view.decision();
    if (decision != null && !"allow".equals(decision) && !"deny".equals(decision)) {
      throw new ClavenarTransportException(
          "clavenar poll with unexpected decision: " + decision, 200);
    }
    return view;
  }

  private static HttpResponse<String> send(
      HttpClient client, HttpRequest request, Duration timeout, String op) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ClavenarTransportException(
          "clavenar " + op + " timed out after " + timeout.toMillis() + "ms");
    } catch (java.io.IOException e) {
      throw new ClavenarTransportException("clavenar " + op + " failed: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ClavenarTransportException("clavenar " + op + " interrupted");
    }
  }

  private static Verdict parseDeny(String body, String corr) {
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(body);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar 403 with unparseable body: " + e.getMessage(), 403);
    }
    if (root == null || !root.isObject() || !root.path("error").isTextual()) {
      throw new ClavenarTransportException("clavenar 403 with unexpected body shape: " + body, 403);
    }
    String layer = root.path("layer").isTextual() ? root.get("layer").asText() : null;
    String intent =
        root.path("intent_category").isTextual() ? root.get("intent_category").asText() : "";
    return new Verdict(
        VerdictKind.DENY,
        corr,
        stringList(root.get("reasons")),
        stringList(root.get("review_reasons")),
        intent,
        layer,
        parseVerdictDetail(root.get("detail")));
  }

  /**
   * Parse the optional verbose-verdict {@code detail} block. Lenient — a missing or malformed block
   * yields null (the gateway omits it unless CLAVENAR_PROXY_VERBOSE_VERDICTS=true).
   */
  private static VerdictDetail parseVerdictDetail(JsonNode node) {
    if (node == null || !node.isObject() || !node.path("detectors").isArray()) {
      return null;
    }
    java.util.List<VerdictDetail.DetectorScore> detectors = new java.util.ArrayList<>();
    for (JsonNode d : node.get("detectors")) {
      if (!d.isObject() || !d.path("detector").isTextual() || !d.path("score").isNumber()) {
        continue;
      }
      detectors.add(
          new VerdictDetail.DetectorScore(
              d.get("detector").asText(),
              d.get("score").asDouble(),
              d.path("flagged").asBoolean(false)));
    }
    java.util.List<String> degraded =
        node.path("degraded").isArray() ? stringList(node.get("degraded")) : java.util.List.of();
    return new VerdictDetail(detectors, degraded);
  }

  private static Verdict parsePending(String body, String corr) {
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(body);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar 202 with unparseable body: " + e.getMessage(), 202);
    }
    boolean ok =
        root != null
            && root.isObject()
            && "pending".equals(root.path("status").asText(""))
            && root.path("correlation_id").isTextual()
            && root.path("review_reasons").isArray();
    if (!ok) {
      throw new ClavenarTransportException("clavenar 202 with unexpected body shape: " + body, 202);
    }
    String id = corr != null && !corr.isEmpty() ? corr : root.get("correlation_id").asText();
    if (id == null || id.isEmpty()) {
      throw new ClavenarTransportException(
          "clavenar 202 missing correlation id (header and body both empty)", 202);
    }
    return new Verdict(
        VerdictKind.PENDING, id, null, stringList(root.get("review_reasons")), null, null);
  }

  /**
   * Parse the 429 envelope. Lenient like the deny parser: only the string {@code error} code is
   * required; the verdict falls back to {@code rate_limited} when the body omits it (both codes
   * ride HTTP 429). A 429 is a verdict, not a transient failure — it is never retried.
   */
  private static Verdict parseRateLimit(String body, String corr) {
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(body);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar 429 with unparseable body: " + e.getMessage(), 429);
    }
    if (root == null || !root.isObject() || !root.path("error").isTextual()) {
      throw new ClavenarTransportException("clavenar 429 with unexpected body shape: " + body, 429);
    }
    JsonNode verdict = root.path("verdict");
    String code =
        verdict.isTextual() && "quota_exceeded".equals(verdict.asText())
            ? "quota_exceeded"
            : "rate_limited";
    Integer retryAfterSecs =
        root.path("retry_after_secs").isNumber() ? root.get("retry_after_secs").intValue() : null;
    String layer = root.path("layer").isTextual() ? root.get("layer").asText() : null;
    String id = corr;
    if ((id == null || id.isEmpty()) && root.path("correlation_id").isTextual()) {
      id = root.get("correlation_id").asText();
    }
    return Verdict.rateLimited(id, code, stringList(root.get("reasons")), retryAfterSecs, layer);
  }

  private static List<String> stringList(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode e : node) {
        if (e.isTextual()) {
          out.add(e.asText());
        }
      }
    }
    return out;
  }

  private static boolean isRetriable(ClavenarTransportException e) {
    if (e.status() == 0) {
      return true;
    }
    return e.status() >= 500 && e.status() < 600;
  }

  private static long backoffMillis(Duration base, int attempt) {
    long ceiling = base.toMillis() << attempt;
    return (long) (ceiling * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ClavenarTransportException("clavenar inspect interrupted during backoff");
    }
  }

  /**
   * Join base + path, trimming one trailing/leading slash. Deliberately not {@code URI.resolve},
   * which drops a base path for partners on an endpoint like {@code https://gw/clavenar}.
   */
  static String joinUrl(String base, String path) {
    String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    String p = path.startsWith("/") ? path.substring(1) : path;
    return b + "/" + p;
  }
}
