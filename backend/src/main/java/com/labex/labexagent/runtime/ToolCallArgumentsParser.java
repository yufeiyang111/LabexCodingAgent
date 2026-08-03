package com.labex.labexagent.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 将 Provider 的 function.arguments 分类为可执行 JSON object，禁止解析失败回退为空对象。 */
final class ToolCallArgumentsParser {
    private ToolCallArgumentsParser() {
    }

    static ParseResult parse(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return ParseResult.rejected(Status.MISSING);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(rawArguments);
        } catch (RuntimeException invalidJson) {
            return ParseResult.rejected(Status.INVALID_JSON);
        }
        if (!parsed.isJsonObject()) {
            return ParseResult.rejected(Status.NON_OBJECT);
        }
        return new ParseResult(Status.VALID, parsed.getAsJsonObject());
    }

    enum Status {
        VALID,
        MISSING,
        INVALID_JSON,
        NON_OBJECT
    }

    record ParseResult(Status status, JsonObject arguments) {
        ParseResult {
            status = status == null ? Status.INVALID_JSON : status;
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
        }

        static ParseResult rejected(Status status) {
            return new ParseResult(status, new JsonObject());
        }

        boolean valid() {
            return status == Status.VALID;
        }

        String reasonCode() {
            return status.name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}