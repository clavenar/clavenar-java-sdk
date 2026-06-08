package com.clavenar.agentsdk;

import java.time.Duration;

/** Tunes {@link ClavenarPending#resolve}. Defaults: a 2s poll interval and a 10-minute ceiling. */
public record ResolveOptions(Duration pollInterval, Duration timeout) {
  public static ResolveOptions defaults() {
    return new ResolveOptions(Duration.ofSeconds(2), Duration.ofMinutes(10));
  }
}
