package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ToolCallAccumulatorTest {

    @Test
    void ignoresBlankNameInLaterToolCallDelta() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();
        accumulator.append(call(0, "call-read", "read_file", "{\"path\":"));
        accumulator.append(call(0, null, "", "\"README.md\"}"));

        ToolCallAccumulator.ToolCall completed = accumulator.completedCalls().get(0);

        assertEquals("read_file", completed.name());
        assertEquals("call-read", completed.id());
        assertEquals("{\"path\":\"README.md\"}", completed.arguments());
    }

    private static JsonObject call(int index, String id, String name, String arguments) {
        JsonObject call = new JsonObject();
        call.addProperty("index", index);
        if (id != null) call.addProperty("id", id);
        JsonObject function = new JsonObject();
        if (name != null) function.addProperty("name", name);
        if (arguments != null) function.addProperty("arguments", arguments);
        call.add("function", function);
        return call;
    }
}
