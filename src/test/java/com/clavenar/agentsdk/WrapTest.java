package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WrapTest {

  // Fake Anthropic-shaped client (interfaces so the dynamic Proxy can wrap them).
  interface FakeAnthropic {
    FakeMessages messages();
  }

  interface FakeMessages {
    Object create(Object params);
  }

  interface FakeStreamingMessages extends FakeMessages {
    Object createStreaming(Object params);
  }

  record FakeBlock(String type, String id, String name, Map<String, Object> input) {}

  record FakeMessage(List<FakeBlock> content) {}

  record FakeStoppedMessage(List<FakeBlock> content, String stop_reason) {}

  // Fake OpenAI-shaped client.
  interface FakeOpenAI {
    FakeChat chat();
  }

  interface FakeChat {
    FakeCompletions completions();
  }

  interface FakeCompletions {
    Object create(Object params);
  }

  record FakeFn(String name, String arguments) {}

  record FakeToolCall(String id, String type, FakeFn function) {}

  record FakeMsg(List<FakeToolCall> tool_calls) {}

  record FakeChoice(FakeMsg message) {}

  record FakeCompletion(List<FakeChoice> choices) {}

  @Test
  void wrapAnthropicDeny() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}"))) {
      FakeMessages msgs =
          params ->
              new FakeMessage(
                  List.of(
                      new FakeBlock(
                          "tool_use", "toolu_1", "delete_user", Map.of("user", "alice"))));
      FakeAnthropic client = () -> msgs;
      FakeAnthropic wrapped = Clavenar.wrap(client, Fixtures.opts(srv.baseUrl));
      ClavenarDenied d =
          assertThrows(ClavenarDenied.class, () -> wrapped.messages().create(new Object()));
      assertEquals("delete_user", d.toolName());
    }
  }

  @Test
  void wrapOpenAiDeny() throws Exception {
    try (TestServer srv =
        new TestServer(
            (m, p, b, h) ->
                TestServer.Response.of(403, "{\"error\":\"x\",\"reasons\":[\"no\"]}"))) {
      FakeCompletions comps =
          params ->
              new FakeCompletion(
                  List.of(
                      new FakeChoice(
                          new FakeMsg(
                              List.of(
                                  new FakeToolCall(
                                      "call_1",
                                      "function",
                                      new FakeFn("delete_user", "{\"u\":1}")))))));
      FakeChat chat = () -> comps;
      FakeOpenAI client = () -> chat;
      FakeOpenAI wrapped = Clavenar.wrap(client, Fixtures.opts(srv.baseUrl));
      ClavenarDenied d =
          assertThrows(
              ClavenarDenied.class, () -> wrapped.chat().completions().create(new Object()));
      assertEquals("delete_user", d.toolName());
    }
  }

  @Test
  void wrapAllowPassesThrough() throws Exception {
    try (TestServer srv = new TestServer((m, p, b, h) -> TestServer.Response.of(200, null))) {
      FakeMessages msgs =
          params ->
              new FakeMessage(
                  List.of(new FakeBlock("tool_use", "toolu_1", "ok_tool", Map.of("a", 1))));
      FakeAnthropic client = () -> msgs;
      FakeAnthropic wrapped = Clavenar.wrap(client, Fixtures.opts(srv.baseUrl));
      Object result = wrapped.messages().create(new Object());
      assertEquals(FakeMessage.class, result.getClass());
    }
  }

  @Test
  void unrecognizedClientThrows() {
    Object stranger = new Object();
    assertThrows(
        ClavenarConfigException.class,
        () -> Clavenar.wrap(stranger, Fixtures.opts("http://127.0.0.1:9")));
  }

  @Test
  void wrapBlocksStreamingCreate() {
    boolean[] invoked = {false};
    FakeStreamingMessages msgs =
        new FakeStreamingMessages() {
          @Override
          public Object create(Object params) {
            return new FakeMessage(List.of());
          }

          @Override
          public Object createStreaming(Object params) {
            invoked[0] = true;
            return new Object();
          }
        };
    FakeAnthropic client = () -> msgs;
    FakeAnthropic wrapped = Clavenar.wrap(client, Fixtures.opts("http://127.0.0.1:9"));
    ClavenarConfigException e =
        assertThrows(
            ClavenarConfigException.class,
            () -> ((FakeStreamingMessages) wrapped.messages()).createStreaming(new Object()));
    assertTrue(e.getMessage().contains("StreamGate"));
    assertFalse(invoked[0], "streaming call must be blocked before reaching the provider");
  }

  @Test
  void wrapAllowsStreamingWithExplicitOptOut() {
    FakeStreamingMessages msgs =
        new FakeStreamingMessages() {
          @Override
          public Object create(Object params) {
            return new FakeMessage(List.of());
          }

          @Override
          public Object createStreaming(Object params) {
            return "raw-stream";
          }
        };
    FakeAnthropic client = () -> msgs;
    ClavenarOptions opts =
        ClavenarOptions.builder("http://127.0.0.1:9").allowUninspectedStream(true).build();
    FakeAnthropic wrapped = Clavenar.wrap(client, opts);
    Object result = ((FakeStreamingMessages) wrapped.messages()).createStreaming(new Object());
    assertEquals("raw-stream", result);
  }

  // Provider-shape drift: the turn declares tool use but the blocks aren't extractable. The call
  // must still pass through (fail-open by contract) while declaresToolUse flags the mismatch.
  @Test
  void driftedAnthropicShapePassesThroughButFlagsMismatch() throws Exception {
    FakeMessages msgs =
        params ->
            new FakeStoppedMessage(
                List.of(new FakeBlock("tool_use_v2", "toolu_1", "delete_user", Map.of())),
                "tool_use");
    FakeAnthropic client = () -> msgs;
    FakeAnthropic wrapped = Clavenar.wrap(client, Fixtures.opts("http://127.0.0.1:9"));
    Object result = wrapped.messages().create(new Object());
    assertEquals(FakeStoppedMessage.class, result.getClass());
    assertTrue(Clavenar.declaresToolUse(Json.MAPPER.valueToTree(result)));
  }

  @Test
  void declaresToolUseMatchesProviderStopMarkers() throws Exception {
    assertTrue(Clavenar.declaresToolUse(Json.MAPPER.readTree("{\"stop_reason\":\"tool_use\"}")));
    assertTrue(
        Clavenar.declaresToolUse(
            Json.MAPPER.readTree("{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}")));
    assertFalse(Clavenar.declaresToolUse(Json.MAPPER.readTree("{\"stop_reason\":\"end_turn\"}")));
    assertFalse(
        Clavenar.declaresToolUse(
            Json.MAPPER.readTree("{\"choices\":[{\"finish_reason\":\"stop\"}]}")));
    assertFalse(Clavenar.declaresToolUse(Json.MAPPER.readTree("{}")));
  }
}
