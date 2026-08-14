package com.labex.labexagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemaCanonicalizerTest {
    private static final Gson GSON = new Gson();

    @Test
    void canonicalizesToolAndSchemaOrderingBeforeProviderRequest() {
        List<Map<String, Object>> first = ToolSchemaCanonicalizer.openAiTools(List.of(
                tool("zeta", false), tool("alpha", false)));
        List<Map<String, Object>> second = ToolSchemaCanonicalizer.openAiTools(List.of(
                tool("alpha", true), tool("zeta", true)));

        String firstJson = GSON.toJson(first);
        String secondJson = GSON.toJson(second);
        assertEquals(firstJson, secondJson);
        assertEquals(List.of("alpha", "zeta"), toolNames(first));

        Map<String, Object> parameters = parameters(first.get(0));
        assertEquals(List.of("properties", "required", "type"), new ArrayList<>(parameters.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertEquals(List.of("alpha", "zeta"), new ArrayList<>(properties.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> alpha = (Map<String, Object>) properties.get("alpha");
        assertEquals(List.of("description", "type"), new ArrayList<>(alpha.keySet()));

    }

    private static ToolDefinition tool(String name, boolean reverseSchemaOrder) {
        Map<String, Object> alpha = property("alpha property", reverseSchemaOrder);
        Map<String, Object> zeta = property("zeta property", !reverseSchemaOrder);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (reverseSchemaOrder) {
            properties.put("zeta", zeta);
            properties.put("alpha", alpha);
        } else {
            properties.put("alpha", alpha);
            properties.put("zeta", zeta);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        if (reverseSchemaOrder) {
            schema.put("type", "object");
            schema.put("required", List.of("alpha"));
            schema.put("properties", properties);
        } else {
            schema.put("properties", properties);
            schema.put("required", List.of("alpha"));
            schema.put("type", "object");
        }
        return new ToolDefinition(name, name + " tool", schema);
    }

    private static Map<String, Object> property(String description, boolean reverseOrder) {
        Map<String, Object> property = new LinkedHashMap<>();
        if (reverseOrder) {
            property.put("type", "string");
            property.put("description", description);
        } else {
            property.put("description", description);
            property.put("type", "string");
        }
        return property;
    }

    private static List<String> toolNames(List<Map<String, Object>> tools) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            names.add(String.valueOf(function.get("name")));
        }
        return names;
    }

    private static Map<String, Object> parameters(Map<String, Object> tool) {
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tool.get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        return parameters;
    }
}
