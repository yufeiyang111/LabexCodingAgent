package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.impl.TaskTool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolArgumentSchemaValidatorTest {
    private final ToolArgumentSchemaValidator validator = new ToolArgumentSchemaValidator();

    @Test
    void acceptsArgumentsThatMatchRequiredFieldsAndTypes() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("read_file")
                .description("read")
                .stringProperty("file_path", "path", true)
                .intProperty("max_lines", "limit", false)
                .build();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("file_path", "README.md");
        arguments.addProperty("max_lines", 20);

        ToolArgumentSchemaValidator.Validation result = validator.validate(definition, arguments);

        assertTrue(result.valid());
        assertEquals("ok", result.code());
    }

    @Test
    void rejectsMissingRequiredUnknownAndWrongTypeArguments() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("read_file")
                .description("read")
                .stringProperty("file_path", "path", true)
                .intProperty("max_lines", "limit", false)
                .build();

        ToolArgumentSchemaValidator.Validation missing = validator.validate(definition, new JsonObject());
        JsonObject unknownArgs = new JsonObject();
        unknownArgs.addProperty("file_path", "README.md");
        unknownArgs.addProperty("command", "whoami");
        ToolArgumentSchemaValidator.Validation unknown = validator.validate(definition, unknownArgs);
        JsonObject wrongTypeArgs = new JsonObject();
        wrongTypeArgs.addProperty("file_path", "README.md");
        wrongTypeArgs.addProperty("max_lines", "twenty");
        ToolArgumentSchemaValidator.Validation wrongType = validator.validate(definition, wrongTypeArgs);

        assertFalse(missing.valid());
        assertEquals("missing_required", missing.code());
        assertFalse(unknown.valid());
        assertEquals("unknown_field", unknown.code());
        assertFalse(wrongType.valid());
        assertEquals("type_mismatch", wrongType.code());
    }

    @Test
    void taskToolAcceptsBooleanBackgroundAndDoesNotAdvertiseUnusedTaskId() {
        ToolDefinition definition = new TaskTool(null, null).definition();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("description", "research tool contracts");
        arguments.addProperty("background", true);

        ToolArgumentSchemaValidator.Validation result = validator.validate(definition, arguments);

        assertTrue(result.valid());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
        assertFalse(properties.containsKey("task_id"));
    }

    @Test
    void validatesNestedObjectsAndArraysWithoutCoercion() {
        ToolDefinition definition = new ToolDefinition("nested", "nested", Map.of(
                "type", "object",
                "required", java.util.List.of("items"),
                "properties", Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "required", java.util.List.of("enabled"),
                                        "properties", Map.of("enabled", Map.of("type", "boolean")))))));
        JsonObject arguments = com.google.gson.JsonParser.parseString(
                "{\"items\":[{\"enabled\":\"true\"}]}").getAsJsonObject();

        ToolArgumentSchemaValidator.Validation result = validator.validate(definition, arguments);

        assertFalse(result.valid());
        assertEquals("type_mismatch", result.code());
        assertTrue(result.path().contains("items[0].enabled"));
    }
}
