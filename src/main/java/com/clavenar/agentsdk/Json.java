package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson mapper for the SDK. */
final class Json {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}
}
