package com.labex.labexagent.llm.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Converts LabexAgent standard tool JSON maps into LangChain4j ToolSpecifications.
 */
public final class Langchain4jToolConverter {

    private Langchain4jToolConverter() {}

    /**
     * Converts a list of tool maps (OpenAI-compatible schema) into LangChain4j ToolSpecifications.
     */
    @SuppressWarnings("unchecked")
    public static List<ToolSpecification> convert(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolSpecification> specifications = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if (tool == null || tool.isEmpty()) {
                continue;
            }

            String name = "";
            String description = "";
            Map<String, Object> parameters = null;

            Object functionObj = tool.get("function");
            if (functionObj instanceof Map<?, ?> fnMap) {
                Object fnName = fnMap.get("name");
                name = fnName != null ? String.valueOf(fnName) : "";
                Object fnDesc = fnMap.get("description");
                description = fnDesc != null ? String.valueOf(fnDesc) : "";
                Object paramsObj = fnMap.get("parameters");
                if (paramsObj instanceof Map<?, ?> pMap) {
                    parameters = (Map<String, Object>) pMap;
                }
            } else if (tool.containsKey("name")) {
                Object toolName = tool.get("name");
                name = toolName != null ? String.valueOf(toolName) : "";
                Object toolDesc = tool.get("description");
                description = toolDesc != null ? String.valueOf(toolDesc) : "";
                Object paramsObj = tool.get("parameters");
                if (paramsObj instanceof Map<?, ?> pMap) {
                    parameters = (Map<String, Object>) pMap;
                }
            }

            if (name.isBlank()) {
                continue;
            }

            var builder = ToolSpecification.builder()
                    .name(name)
                    .description(description);

            if (parameters != null) {
                builder.parameters(convertJsonObjectSchema(parameters));
            } else {
                builder.parameters(JsonObjectSchema.builder().build());
            }

            specifications.add(builder.build());
        }

        return specifications;
    }

    @SuppressWarnings("unchecked")
    public static JsonObjectSchema convertJsonObjectSchema(Map<String, Object> schemaMap) {
        if (schemaMap == null) {
            return JsonObjectSchema.builder().build();
        }

        var builder = JsonObjectSchema.builder();

        Object descObj = schemaMap.get("description");
        if (descObj instanceof String desc && !desc.isBlank()) {
            builder.description(desc);
        }

        Object propsObj = schemaMap.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> propMap) {
                    builder.addProperty(propName, convertElement((Map<String, Object>) propMap));
                }
            }
        }

        Object reqObj = schemaMap.get("required");
        if (reqObj instanceof List<?> reqList) {
            List<String> required = new ArrayList<>();
            for (Object r : reqList) {
                if (r != null) {
                    required.add(String.valueOf(r));
                }
            }
            if (!required.isEmpty()) {
                builder.required(required);
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonSchemaElement convertElement(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return JsonStringSchema.builder().build();
        }

        Object rawType = map.get("type");
        String type = rawType != null ? String.valueOf(rawType).toLowerCase() : "string";
        String description = map.get("description") instanceof String d ? d : null;

        // Check enum
        Object enumObj = map.get("enum");
        if (enumObj instanceof List<?> enumList && !enumList.isEmpty()) {
            List<String> enumValues = new ArrayList<>();
            for (Object e : enumList) {
                if (e != null) enumValues.add(String.valueOf(e));
            }
            var enumBuilder = JsonEnumSchema.builder().enumValues(enumValues);
            if (description != null) enumBuilder.description(description);
            return enumBuilder.build();
        }

        switch (type) {
            case "integer": {
                var b = JsonIntegerSchema.builder();
                if (description != null) b.description(description);
                return b.build();
            }
            case "number": {
                var b = JsonNumberSchema.builder();
                if (description != null) b.description(description);
                return b.build();
            }
            case "boolean": {
                var b = JsonBooleanSchema.builder();
                if (description != null) b.description(description);
                return b.build();
            }
            case "array": {
                var b = JsonArraySchema.builder();
                if (description != null) b.description(description);
                Object itemsObj = map.get("items");
                if (itemsObj instanceof Map<?, ?> itemMap) {
                    b.items(convertElement((Map<String, Object>) itemMap));
                } else {
                    b.items(JsonStringSchema.builder().build());
                }
                return b.build();
            }
            case "object":
                return convertJsonObjectSchema(map);
            case "string":
            default: {
                var b = JsonStringSchema.builder();
                if (description != null) b.description(description);
                return b.build();
            }
        }
    }
}
