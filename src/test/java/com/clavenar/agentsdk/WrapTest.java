package com.clavenar.agentsdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

  record FakeBlock(String type, String id, String name, Map<String, Object> input) {}

  record FakeMessage(List<FakeBlock> content) {}

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
}
