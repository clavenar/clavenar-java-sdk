package com.clavenar.agentsdk;

import java.time.Duration;

/**
 * Per-inspection retry policy. Network errors and 5xx responses retry up to {@code maxAttempts}
 * with full-jitter exponential backoff ({@code baseDelay * 2^attempt}); 200 / 403 / other-4xx never
 * retry.
 */
public record RetryOptions(int maxAttempts, Duration baseDelay) {
  public static RetryOptions defaults() {
    return new RetryOptions(3, Duration.ofMillis(100));
  }
}
