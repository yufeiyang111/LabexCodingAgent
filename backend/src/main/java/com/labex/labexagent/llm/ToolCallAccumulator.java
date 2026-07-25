package com.labex.labexagent.llm;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Accumulates OpenAI-compatible streaming tool deltas by their stable call index. */
final class ToolCallAccumulator {
    private final Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();

    ToolCallDelta append(JsonObject rawCall) {
        int index = integer(rawCall, "index", calls.size());
        MutableToolCall call = calls.computeIfAbsent(index, MutableToolCall::new);
        if (rawCall.has("id") && !rawCall.get("id").isJsonNull()) {
            call.id = rawCall.get("id").getAsString();
        }
        String delta = "";
        if (rawCall.has("function") && rawCall.get("function").isJsonObject()) {
            JsonObject function = rawCall.getAsJsonObject("function");
            if (function.has("name") && !function.get("name").isJsonNull()) {
                call.name = function.get("name").getAsString();
            }
            if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                delta = function.get("arguments").getAsString();
                call.arguments.append(delta);
            }
        }
        return new ToolCallDelta(call.snapshot(), delta);
    }

    List<ToolCall> completedCalls() {
        return calls.values().stream()
                .map(MutableToolCall::snapshot)
                .filter(call -> call.name() != null && !call.name().isBlank())
                .sorted(Comparator.comparingInt(ToolCall::index))
                .toList();
    }

    private int integer(JsonObject object, String field, int fallback) {
        try {
            return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    record ToolCall(String id, int index, String name, String arguments) {
    }

    record ToolCallDelta(ToolCall call, String argumentsDelta) {
    }

    private static final class MutableToolCall {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private MutableToolCall(int index) {
            this.index = index;
        }

        private ToolCall snapshot() {
            return new ToolCall(id, index, name, arguments.toString());
        }
    }
}
