package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.labex.labexagent.tool.ToolDefinition;
import java.math.BigDecimal;
import java.util.Map;

/** 对进入执行边界的工具参数执行本轮工具 schema 的确定性最小校验。 */
final class ToolArgumentSchemaValidator {
    private static final Gson GSON = new Gson();

    Validation validate(ToolDefinition definition, JsonObject arguments) {
        if (definition == null) {
            return Validation.rejected("unknown_tool", "$", "tool definition is unavailable");
        }
        JsonElement schemaElement = GSON.toJsonTree(definition.getInputSchema() == null
                ? Map.of("type", "object", "properties", Map.of())
                : definition.getInputSchema());
        if (!schemaElement.isJsonObject()) {
            return Validation.rejected("invalid_schema", "$", "tool input schema is not an object");
        }
        return validateValue(schemaElement.getAsJsonObject(), arguments == null ? new JsonObject() : arguments, "$", true);
    }

    private Validation validateValue(JsonObject schema, JsonElement value, String path, boolean rejectUnknownFields) {
        Validation enumValidation = validateEnum(schema, value, path);
        if (!enumValidation.valid()) {
            return enumValidation;
        }
        String type = schema.has("type") && schema.get("type").isJsonPrimitive()
                ? schema.get("type").getAsString() : "";
        if (!type.isBlank() && !matchesType(type, value)) {
            return Validation.rejected("type_mismatch", path, "argument does not match schema type " + type);
        }
        if ("object".equals(type) || (type.isBlank() && value != null && value.isJsonObject())) {
            return validateObject(schema, value.getAsJsonObject(), path, rejectUnknownFields);
        }
        if ("array".equals(type) && schema.has("items") && schema.get("items").isJsonObject()) {
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                Validation nested = validateValue(schema.getAsJsonObject("items"), array.get(index),
                        path + "[" + index + "]", rejectUnknownFields);
                if (!nested.valid()) {
                    return nested;
                }
            }
        }
        return Validation.ok();
    }

    private Validation validateObject(JsonObject schema, JsonObject value, String path, boolean rejectUnknownFields) {
        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                if (!required.isJsonPrimitive() || !required.getAsJsonPrimitive().isString()) {
                    return Validation.rejected("invalid_schema", path, "required field name is invalid");
                }
                String field = required.getAsString();
                if (!value.has(field) || value.get(field).isJsonNull()) {
                    return Validation.rejected("missing_required", child(path, field), "required argument is missing");
                }
            }
        }

        boolean allowsUnknown = schema.has("additionalProperties")
                && schema.get("additionalProperties").isJsonPrimitive()
                && schema.get("additionalProperties").getAsBoolean();
        for (String field : value.keySet()) {
            if (!properties.has(field)) {
                if (rejectUnknownFields && !allowsUnknown) {
                    return Validation.rejected("unknown_field", child(path, field), "argument is not declared by the tool schema");
                }
                continue;
            }
            JsonElement propertySchema = properties.get(field);
            if (!propertySchema.isJsonObject()) {
                return Validation.rejected("invalid_schema", child(path, field), "property schema is not an object");
            }
            Validation nested = validateValue(propertySchema.getAsJsonObject(), value.get(field), child(path, field), rejectUnknownFields);
            if (!nested.valid()) {
                return nested;
            }
        }
        return Validation.ok();
    }

    private Validation validateEnum(JsonObject schema, JsonElement value, String path) {
        if (!schema.has("enum") || !schema.get("enum").isJsonArray()) {
            return Validation.ok();
        }
        for (JsonElement allowed : schema.getAsJsonArray("enum")) {
            if (allowed.equals(value)) {
                return Validation.ok();
            }
        }
        return Validation.rejected("enum_mismatch", path, "argument is not one of the allowed values");
    }

    private boolean matchesType(String type, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null".equals(type);
        }
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> isInteger(value);
            case "null" -> value.isJsonNull();
            default -> true;
        };
    }

    private boolean isInteger(JsonElement value) {
        if (!value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return false;
        }
        try {
            return new BigDecimal(primitive.getAsString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
    }

    private String child(String path, String field) {
        return "$".equals(path) ? field : path + "." + field;
    }

    record Validation(boolean valid, String code, String path, String message) {
        static Validation ok() {
            return new Validation(true, "ok", "$", "");
        }

        static Validation rejected(String code, String path, String message) {
            return new Validation(false, code, path, message);
        }
    }
}
