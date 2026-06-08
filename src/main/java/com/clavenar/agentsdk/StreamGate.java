package com.clavenar.agentsdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates streaming tool-call fragments and inspects an assembled batch when the caller signals
 * a close. A provider stream wrapper drives it: {@link #start} when a tool call opens, {@link
 * #update} for each fragment, then {@link #close} / {@link #closeByPrefix} — called BEFORE the
 * closing event is forwarded — to inspect. Close throws the same errors {@code inspectAll} would,
 * so the wrapper can stop the stream before releasing the closing event on a deny.
 *
 * <p>Not safe for concurrent use; drive it from the single stream-reading thread.
 */
public final class StreamGate {
  private final ClavenarInspector inspector;
  private final Map<String, ToolBuf> bufs = new LinkedHashMap<>();

  public StreamGate(ClavenarOptions opts) {
    this.inspector = new ClavenarInspector(opts);
  }

  /** Register an opening tool call under key with its id and name (the Anthropic block index). */
  public void start(String key, String id, String name) {
    ToolBuf b = bufs.computeIfAbsent(key, k -> new ToolBuf());
    b.id = id;
    b.name = name;
  }

  /** Merge a fragment into key, creating it if no start arrived first (the OpenAI delta case). */
  public void update(String key, String id, String name, String argsFragment) {
    ToolBuf b = bufs.computeIfAbsent(key, k -> new ToolBuf());
    if (id != null && !id.isEmpty()) {
      b.id = id;
    }
    if (name != null && !name.isEmpty()) {
      b.name = name;
    }
    if (argsFragment != null && !argsFragment.isEmpty()) {
      b.args.append(argsFragment);
    }
  }

  /** Whether a tool-call buffer is open under key. */
  public boolean has(String key) {
    return bufs.containsKey(key);
  }

  /** Assemble and inspect the buffered calls for the given keys. Unknown keys are skipped. */
  public void close(String... keys) {
    List<NormalizedToolCall> calls = new ArrayList<>();
    for (String key : keys) {
      ToolBuf b = bufs.remove(key);
      if (b != null) {
        calls.add(b.toCall());
      }
    }
    if (!calls.isEmpty()) {
      inspector.inspectAll(calls);
    }
  }

  /**
   * Close every open key with the given prefix, in first-seen order — the OpenAI per-choice drain.
   */
  public void closeByPrefix(String prefix) {
    List<String> keys = new ArrayList<>();
    for (String k : bufs.keySet()) {
      if (k.startsWith(prefix)) {
        keys.add(k);
      }
    }
    close(keys.toArray(new String[0]));
  }

  private static final class ToolBuf {
    private String id;
    private String name;
    private final StringBuilder args = new StringBuilder();

    NormalizedToolCall toCall() {
      if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
        throw new ClavenarConfigException("clavenar stream: tool call buffer missing id or name");
      }
      String raw = args.toString();
      if (raw.isEmpty()) {
        return new NormalizedToolCall(id, name, Json.MAPPER.createObjectNode());
      }
      JsonNode node;
      try {
        node = Json.MAPPER.readTree(raw);
      } catch (Exception e) {
        throw new ClavenarConfigException(
            "clavenar stream: tool call " + id + " (" + name + ") has unparseable arguments");
      }
      return new NormalizedToolCall(id, name, node);
    }
  }
}
