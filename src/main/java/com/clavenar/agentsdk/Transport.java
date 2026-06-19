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
import java.util.concurrent.ThreadLocalRandom;

/** Wire transport: POST /mcp inspection with retry, and GET /pending/{id} polling. */
final class Transport {
  private static final String CORRELATION_HEADER = "X-Clavenar-Correlation-Id";

  private Transport() {}

  static Verdict inspect(NormalizedToolCall call, ClavenarOptions o) {
    RetryOptions r = o.retry();
    if (r.maxAttempts() < 1) {
      throw new ClavenarTransportException(
          "clavenar: retry.maxAttempts must be >= 1, got " + r.maxAttempts());
    }
    ClavenarTransportException last = null;
    for (int attempt = 0; attempt < r.maxAttempts(); attempt++) {
      try {
        return inspectOnce(call, o);
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

  private static Verdict inspectOnce(NormalizedToolCall call, ClavenarOptions o) {
    ObjectNode root = Json.MAPPER.createObjectNode();
    root.put("jsonrpc", "2.0");
    root.put("method", "tools/call");
    ObjectNode params = root.putObject("params");
    params.put("name", call.name());
    params.set("arguments", call.input());
    root.put("id", call.id());

    String body;
    try {
      body = Json.MAPPER.writeValueAsString(root);
    } catch (Exception e) {
      throw new ClavenarTransportException(
          "clavenar inspect: failed to encode request: " + e.getMessage());
    }

    HttpRequest.Builder rb =
        HttpRequest.newBuilder(URI.create(joinUrl(o.endpoint(), "/mcp")))
            .timeout(o.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (o.token() != null && !o.token().isEmpty()) {
      rb.header("Authorization", "Bearer " + o.token());
    }

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
      default:
        String text = resp.body() == null ? "" : resp.body().strip();
        String msg = "clavenar inspect: unexpected status " + status;
        if (!text.isEmpty()) {
          msg += ": " + text;
        }
        throw new ClavenarTransportException(msg, status);
    }
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
