package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SecureTransportProfileTest {
  @Test
  void acquiresAndTrimsFreshTokenForEveryRequest() {
    AtomicInteger generation = new AtomicInteger();
    SecureTransportProfile profile =
        SecureTransportProfile.builder(Path.of("ca"), Path.of("cert"), Path.of("key"))
            .tokenSource(() -> " token-" + generation.incrementAndGet() + " ")
            .build();
    assertEquals("token-1", profile.token());
    assertEquals("token-2", profile.token());
  }

  @Test
  void rejectsZeroTimeoutBeforeReadingCredentials() {
    assertThrows(
        ClavenarConfigException.class,
        () ->
            SecureTransportProfile.builder(Path.of("ca"), Path.of("cert"), Path.of("key"))
                .timeouts(Duration.ZERO, Duration.ofSeconds(1))
                .build());
  }

  @Test
  void rejectsEmptyToken() {
    SecureTransportProfile profile =
        SecureTransportProfile.builder(Path.of("ca"), Path.of("cert"), Path.of("key"))
            .tokenSource(() -> " ")
            .build();
    assertThrows(ClavenarConfigException.class, profile::token);
  }

  @Test
  void closeIsIdempotentAndTerminal() {
    SecureTransportProfile profile =
        SecureTransportProfile.builder(Path.of("ca"), Path.of("cert"), Path.of("key"))
            .tokenSource(() -> "token")
            .build();
    profile.close();
    profile.close();
    assertThrows(ClavenarConfigException.class, profile::token);
  }
}
