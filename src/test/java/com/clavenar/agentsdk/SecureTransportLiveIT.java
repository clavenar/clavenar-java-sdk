package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SecureTransportLiveIT {
  @Test
  void realMtlsAndCertificateTokenRotation() throws Exception {
    String endpoint = required("CLAVENAR_SECURE_TRANSPORT_ENDPOINT");
    Path cert = Path.of(required("CLAVENAR_SECURE_TRANSPORT_CLIENT_CERT"));
    Path key = Path.of(required("CLAVENAR_SECURE_TRANSPORT_CLIENT_KEY"));
    AtomicInteger generation = new AtomicInteger();
    SecureTransportProfile profile =
        SecureTransportProfile.builder(Path.of(required("CLAVENAR_SECURE_TRANSPORT_CA")), cert, key)
            .tokenSource(() -> "matrix-token-" + generation.incrementAndGet())
            .build();
    ClavenarOptions options = ClavenarOptions.builder(endpoint).secureTransport(profile).build();
    NormalizedToolCall call =
        new NormalizedToolCall("matrix", "matrix_probe", Json.MAPPER.createObjectNode());
    assertEquals(VerdictKind.ALLOW, Transport.inspect(call, options).kind());

    Files.copy(
        Path.of(required("CLAVENAR_SECURE_TRANSPORT_NEXT_CERT")),
        cert,
        StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        Path.of(required("CLAVENAR_SECURE_TRANSPORT_NEXT_KEY")),
        key,
        StandardCopyOption.REPLACE_EXISTING);
    profile.reload();
    assertEquals(VerdictKind.ALLOW, Transport.inspect(call, options).kind());
    assertEquals(2, generation.get());
    profile.close();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
