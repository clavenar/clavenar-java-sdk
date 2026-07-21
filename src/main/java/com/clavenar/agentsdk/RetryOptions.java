package com.clavenar.agentsdk;

import java.time.Duration;

/**
 * Retry policy for explicit side-effect-free decisions. Network errors and 5xx responses retry up
 * to {@code maxAttempts} with one stable pre-network idempotency ID and full-jitter exponential
 * backoff ({@code baseDelay * 2^attempt}). Effect-capable execution is outside this loop and never
 * retries; 200 / 403 / other-4xx never retry.
 */
public record RetryOptions(int maxAttempts, Duration baseDelay) {
  public static RetryOptions defaults() {
    return new RetryOptions(3, Duration.ofMillis(100));
  }
}
