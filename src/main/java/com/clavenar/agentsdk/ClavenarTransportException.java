package com.clavenar.agentsdk;

/**
 * Thrown when clavenar is unreachable or returns an unexpected response. {@link #status()} is the
 * HTTP status when one was received, or 0 for a network-level failure (which is retriable).
 */
public final class ClavenarTransportException extends ClavenarException {
  private final int status;

  public ClavenarTransportException(String message) {
    this(message, 0);
  }

  public ClavenarTransportException(String message, int status) {
    super(message);
    this.status = status;
  }

  /** The HTTP status, or 0 when the request never received an HTTP response. */
  public int status() {
    return status;
  }
}
