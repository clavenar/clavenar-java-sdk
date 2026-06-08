package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
   * Inspect a batch concurrently and, in enforce mode, throw the first {@link ClavenarDenied} /
   * {@link ClavenarPending} in submission order — not wire order. {@code onVerdict} fires per call
   * before any deny→throw. In observe mode nothing blocks: deny passes through and a per-call
   * transport failure fires {@code onPolicyError} and is treated as allowed.
   */
  public void inspectAll(List<NormalizedToolCall> calls) {
    if (calls == null || calls.isEmpty()) {
      return;
    }
    boolean enforce = opts.mode() == Mode.ENFORCE;

    List<CompletableFuture<Outcome>> futures = new ArrayList<>(calls.size());
    for (NormalizedToolCall call : calls) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return Outcome.ok(Transport.inspect(call, opts));
                } catch (ClavenarTransportException e) {
                  if (!enforce) {
                    return Outcome.fail(e);
                  }
                  throw e; // enforce: fail closed.
                }
              }));
    }

    // Await all first: in enforce, any transport error surfaces here (fail closed) before any deny
    // is processed, matching Promise.all semantics.
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (CompletionException ce) {
      throw unwrap(ce);
    }

    for (int i = 0; i < calls.size(); i++) {
      NormalizedToolCall call = calls.get(i);
      Outcome out = futures.get(i).join();
      VerdictContext ctx = new VerdictContext(call.name(), call.id(), call.input());
      if (out.error != null) {
        if (opts.onPolicyError() != null) {
          opts.onPolicyError().accept(out.error, ctx);
        }
        continue;
      }
      if (opts.onVerdict() != null) {
        opts.onVerdict().accept(out.verdict, ctx);
      }
      if (!enforce) {
        continue;
      }
      switch (out.verdict.kind()) {
        case DENY:
          throw new ClavenarDenied(
              call.name(),
              out.verdict.reasons(),
              out.verdict.reviewReasons(),
              out.verdict.intentCategory(),
              out.verdict.layer(),
              out.verdict.correlationId());
        case PENDING:
          String corr = out.verdict.correlationId();
          throw new ClavenarPending(
              call.name(),
              corr,
              out.verdict.reviewReasons(),
              () -> Transport.pollPendingOnce(corr, opts));
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

  private static ClavenarException unwrap(CompletionException ce) {
    Throwable cause = ce.getCause();
    if (cause instanceof ClavenarException ke) {
      return ke;
    }
    String m = cause != null ? cause.getMessage() : ce.getMessage();
    return new ClavenarTransportException("clavenar inspect failed: " + m);
  }

  private static final class Outcome {
    final Verdict verdict;
    final ClavenarTransportException error;

    private Outcome(Verdict verdict, ClavenarTransportException error) {
      this.verdict = verdict;
      this.error = error;
    }

    static Outcome ok(Verdict v) {
      return new Outcome(v, null);
    }

    static Outcome fail(ClavenarTransportException e) {
      return new Outcome(null, e);
    }
  }
}
