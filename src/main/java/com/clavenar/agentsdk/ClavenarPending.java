package com.clavenar.agentsdk;

import java.util.List;
import java.util.function.Supplier;

/**
 * Thrown (enforce mode) when clavenar parks a tool call for human review. Call {@link #resolve()}
 * to block until an operator decides: it returns normally on approve and throws {@link
 * ClavenarDenied} on deny.
 */
public final class ClavenarPending extends ClavenarException {
  private final String toolName;
  private final String correlationId;
  private final List<String> reviewReasons;
  private final transient Supplier<ClavenarPendingView> pollOnce;

  ClavenarPending(
      String toolName,
      String correlationId,
      List<String> reviewReasons,
      Supplier<ClavenarPendingView> pollOnce) {
    super(
        "clavenar parked tool \""
            + toolName
            + "\" for review (correlation_id="
            + correlationId
            + ")");
    this.toolName = toolName;
    this.correlationId = correlationId;
    this.reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    this.pollOnce = pollOnce;
  }

  public String toolName() {
    return toolName;
  }

  public String correlationId() {
    return correlationId;
  }

  public List<String> reviewReasons() {
    return reviewReasons;
  }

  /** Block until an operator decides, using the default 2s poll interval and 10-minute ceiling. */
  public void resolve() {
    resolve(ResolveOptions.defaults());
  }

  /**
   * Block until an operator decides. Polls {@code GET /pending/{id}} every {@code pollInterval} and
   * returns on approve or throws {@link ClavenarDenied} on deny. Transient transport failures (5xx,
   * network) are swallowed between polls; 401 / 404 are terminal. A blown deadline throws {@link
   * ClavenarTransportException}.
   */
  public void resolve(ResolveOptions opts) {
    ResolveOptions o = opts == null ? ResolveOptions.defaults() : opts;
    long pollMillis = o.pollInterval() != null ? o.pollInterval().toMillis() : 2000;
    long timeoutMillis = o.timeout() != null ? o.timeout().toMillis() : 600_000;
    if (pollMillis <= 0) {
      throw new ClavenarTransportException("ResolveOptions.pollInterval must be positive");
    }
    if (timeoutMillis <= 0) {
      throw new ClavenarTransportException("ResolveOptions.timeout must be positive");
    }

    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    while (System.nanoTime() < deadline) {
      ClavenarPendingView view = null;
      try {
        view = pollOnce.get();
      } catch (ClavenarTransportException e) {
        // 401 / 404 are terminal; everything else (5xx, network) is swallowed and retried.
        if (e.status() == 401 || e.status() == 404) {
          throw e;
        }
      }
      if (view != null && view.decision() != null) {
        if ("allow".equals(view.decision())) {
          return;
        }
        if ("deny".equals(view.decision())) {
          List<String> reasons =
              view.deciderNote() != null && !view.deciderNote().isEmpty()
                  ? List.of(view.deciderNote())
                  : List.of("operator denied");
          throw new ClavenarDenied(
              toolName, reasons, reviewReasons, "PendingDenied", null, correlationId);
        }
      }

      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        break;
      }
      long sleepMillis = Math.min(pollMillis, remainingNanos / 1_000_000L);
      if (sleepMillis <= 0) {
        break;
      }
      try {
        Thread.sleep(sleepMillis);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new ClavenarTransportException("clavenar pending resolve interrupted");
      }
    }
    throw new ClavenarTransportException(
        "clavenar pending " + correlationId + " not decided within " + timeoutMillis + "ms");
  }
}
