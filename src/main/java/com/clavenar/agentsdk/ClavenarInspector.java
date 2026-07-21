package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The primary inspection surface for framework integrations (LangChain4j {@code ToolExecutor},
 * Spring AI {@code ToolCallback}): build {@link NormalizedToolCall}s at your tool-dispatch boundary
 * and inspect them before running the tools.
 */
public final class ClavenarInspector {
  private final ClavenarOptions opts;

  public ClavenarInspector(ClavenarOptions opts) {
    opts.validate();
    this.opts = opts;
  }

  /** Inspect one tool call and return its verdict. Never throws on a deny — the caller decides. */
  public Verdict inspect(NormalizedToolCall call) {
    return Transport.inspect(call, opts);
  }

  /** Async variant of {@link #inspect}. */
  public CompletableFuture<Verdict> inspectAsync(NormalizedToolCall call) {
    return CompletableFuture.supplyAsync(() -> Transport.inspect(call, opts));
  }

  /** A single {@code GET /pending/{id}} poll. */
  public ClavenarPendingView pollPendingOnce(String correlationId) {
    return Transport.pollPendingOnce(correlationId, opts);
  }

  /**
   * Inspect a complete sibling set through one ordered atomic decision. In enforce mode the first
   * call in submission order represents a batch deny, pending, or rate-limit verdict.
   */
  public void inspectAll(List<NormalizedToolCall> calls) {
    if (calls == null || calls.isEmpty()) {
      return;
    }
    boolean enforce = opts.mode() == Mode.ENFORCE;

    Verdict verdict;
    try {
      verdict =
          calls.size() == 1
              ? Transport.inspect(calls.get(0), opts)
              : Transport.inspectBatch(calls, opts);
    } catch (ClavenarTransportException error) {
      if (enforce) {
        throw error;
      }
      for (NormalizedToolCall call : calls) {
        if (opts.onPolicyError() != null) {
          opts.onPolicyError()
              .accept(error, new VerdictContext(call.name(), call.id(), call.input()));
        }
      }
      return;
    }

    for (int i = 0; i < calls.size(); i++) {
      NormalizedToolCall call = calls.get(i);
      VerdictContext ctx = new VerdictContext(call.name(), call.id(), call.input());
      if (opts.onVerdict() != null) {
        opts.onVerdict().accept(verdict, ctx);
      }
      if (!enforce) {
        continue;
      }
      switch (verdict.kind()) {
        case DENY:
          ClavenarDenied denied =
              new ClavenarDenied(
                  call.name(),
                  verdict.reasons(),
                  verdict.reviewReasons(),
                  verdict.intentCategory(),
                  verdict.layer(),
                  verdict.correlationId(),
                  verdict.detail());
          if (opts.devMode()) {
            DevMode.emitDenyPanel(denied);
          }
          throw denied;
        case PENDING:
          String corr = verdict.correlationId();
          throw new ClavenarPending(
              call.name(),
              corr,
              verdict.reviewReasons(),
              () -> Transport.pollPendingOnce(corr, opts));
        case RATE_LIMITED:
          throw new ClavenarRateLimited(
              call.name(),
              verdict.rateLimitCode(),
              verdict.reasons(),
              verdict.retryAfterSecs(),
              verdict.layer(),
              verdict.correlationId());
        default:
          break;
      }
    }
  }

  /** Async variant of {@link #inspectAll}. */
  public CompletableFuture<Void> inspectAllAsync(List<NormalizedToolCall> calls) {
    return CompletableFuture.runAsync(() -> inspectAll(calls));
  }

  /** Convenience: inspect one tool call whose arguments are already a {@link JsonNode}. */
  public void enforce(String toolName, String toolCallId, JsonNode arguments) {
    inspectAll(List.of(new NormalizedToolCall(toolCallId, toolName, arguments)));
  }

  /** Convenience: inspect one tool call whose arguments are a JSON-encoded string. */
  public void enforce(String toolName, String toolCallId, String argumentsJson) {
    inspectAll(List.of(NormalizedToolCall.fromJsonArguments(toolCallId, toolName, argumentsJson)));
  }
}
