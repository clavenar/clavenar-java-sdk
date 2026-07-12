package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The wrap-and-forget entry point. {@link #wrap} returns a dynamic proxy over an Anthropic or
 * OpenAI client that intercepts only the {@code create} call: the model's tool calls are inspected
 * before the response reaches the caller; every other method passes through unchanged.
 *
 * <p>For framework integrations where the framework owns the model call (LangChain4j, Spring AI),
 * prefer {@link ClavenarInspector} at the tool-dispatch boundary.
 */
public final class Clavenar {
  private static final System.Logger LOG = System.getLogger(Clavenar.class.getName());
  private static final List<String> STREAMING_CREATE_METHODS =
      List.of("createStreaming", "stream", "createStreamRaw");

  private Clavenar() {}

  /**
   * Wrap a provider client so every tool call is inspected. Structural detection: a client exposing
   * {@code messages()} is treated as Anthropic, one exposing {@code chat()} as OpenAI. The returned
   * proxy is assignable back to the same interface type. Streaming calls ({@code createStreaming} /
   * {@code stream}) cannot be inspected here and throw — use {@link StreamGate}, or opt out with
   * {@link ClavenarOptions.Builder#allowUninspectedStream}.
   *
   * @throws ClavenarConfigException if the client doesn't expose a recognized create surface, or
   *     isn't interface-based (use {@link ClavenarInspector} instead)
   */
  public static <T> T wrap(T client, ClavenarOptions opts) {
    opts.validate();
    ClavenarInspector inspector = new ClavenarInspector(opts);
    if (hasNoArgMethod(client, "messages")) {
      return chainProxy(client, inspector, opts, List.of("messages"), Clavenar::extractAnthropic);
    }
    if (hasNoArgMethod(client, "chat")) {
      return chainProxy(
          client, inspector, opts, List.of("chat", "completions"), Clavenar::extractOpenAI);
    }
    throw new ClavenarConfigException(
        "clavenar: Clavenar.wrap requires a client exposing messages().create() (Anthropic) or "
            + "chat().completions().create() (OpenAI)");
  }

  @SuppressWarnings("unchecked")
  private static <T> T chainProxy(
      T target,
      ClavenarInspector inspector,
      ClavenarOptions opts,
      List<String> accessorChain,
      Function<JsonNode, List<NormalizedToolCall>> extractor) {
    Class<?>[] interfaces = target.getClass().getInterfaces();
    if (interfaces.length == 0) {
      throw new ClavenarConfigException(
          "clavenar: Clavenar.wrap needs an interface-based client; use ClavenarInspector instead");
    }
    InvocationHandler handler =
        (proxy, method, args) -> {
          String name = method.getName();
          if (accessorChain.isEmpty() && "create".equals(name)) {
            Object result = invoke(target, method, args);
            JsonNode tree = Json.MAPPER.valueToTree(result);
            List<NormalizedToolCall> calls = extractor.apply(tree);
            if (calls.isEmpty() && declaresToolUse(tree)) {
              LOG.log(
                  System.Logger.Level.WARNING,
                  "clavenar: response declares tool use (stop_reason/finish_reason) but no tool"
                      + " calls were extracted — the provider response shape may have drifted;"
                      + " tool calls were NOT inspected");
            }
            inspector.inspectAll(calls);
            return result;
          }
          if (accessorChain.isEmpty()
              && STREAMING_CREATE_METHODS.contains(name)
              && !opts.allowUninspectedStream()) {
            throw new ClavenarConfigException(
                "clavenar: "
                    + name
                    + "() streams the response and bypasses Clavenar.wrap inspection, so it is"
                    + " blocked. Gate streamed tool calls with StreamGate, or set"
                    + " allowUninspectedStream(true) to explicitly accept uninspected streaming.");
          }
          if (!accessorChain.isEmpty()
              && accessorChain.get(0).equals(name)
              && (args == null || args.length == 0)) {
            Object sub = invoke(target, method, args);
            return chainProxy(
                sub, inspector, opts, accessorChain.subList(1, accessorChain.size()), extractor);
          }
          return invoke(target, method, args);
        };
    return (T) Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, handler);
  }

  /** True when the provider marked the turn as tool-calling, whatever the content shape. */
  static boolean declaresToolUse(JsonNode tree) {
    if ("tool_use".equals(tree.path("stop_reason").asText())) {
      return true;
    }
    JsonNode choices = tree.path("choices");
    if (choices.isArray()) {
      for (JsonNode choice : choices) {
        if ("tool_calls".equals(choice.path("finish_reason").asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      // Unwrap so the caller sees the real provider exception (and our ClavenarException passes
      // through without being wrapped as an UndeclaredThrowable).
      throw e.getCause() != null ? e.getCause() : e;
    }
  }

  private static List<NormalizedToolCall> extractAnthropic(JsonNode tree) {
    List<NormalizedToolCall> out = new ArrayList<>();
    JsonNode content = tree.path("content");
    if (content.isArray()) {
      for (JsonNode b : content) {
        if ("tool_use".equals(b.path("type").asText())) {
          out.add(
              new NormalizedToolCall(
                  b.path("id").asText(), b.path("name").asText(), b.path("input")));
        }
      }
    }
    return out;
  }

  private static List<NormalizedToolCall> extractOpenAI(JsonNode tree) {
    List<NormalizedToolCall> out = new ArrayList<>();
    JsonNode choices = tree.path("choices");
    if (choices.isArray()) {
      for (JsonNode choice : choices) {
        JsonNode toolCalls = choice.path("message").path("tool_calls");
        if (toolCalls.isArray()) {
          for (JsonNode tc : toolCalls) {
            if (!"function".equals(tc.path("type").asText())) {
              continue;
            }
            out.add(
                NormalizedToolCall.fromJsonArguments(
                    tc.path("id").asText(),
                    tc.path("function").path("name").asText(),
                    tc.path("function").path("arguments").asText("")));
          }
        }
      }
    }
    return out;
  }

  private static boolean hasNoArgMethod(Object client, String name) {
    if (client == null) {
      throw new ClavenarConfigException("clavenar: Clavenar.wrap client must not be null");
    }
    for (Method m : client.getClass().getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == 0) {
        return true;
      }
    }
    return false;
  }
}
