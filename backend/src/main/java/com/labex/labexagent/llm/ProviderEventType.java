package com.labex.labexagent.llm;

public enum ProviderEventType {
    TEXT_DELTA,
    THINKING_DELTA,
    TOOL_ARGUMENTS_DELTA,
    TOOL_CALL,
    USAGE,
    ERROR,
    CANCELLED,
    DONE,
    UNKNOWN;

    public static ProviderEventType fromWireType(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        return switch (type) {
            case "text_delta" -> TEXT_DELTA;
            case "thinking_delta" -> THINKING_DELTA;
            case "tool_args_delta" -> TOOL_ARGUMENTS_DELTA;
            case "tool_call" -> TOOL_CALL;
            case "usage" -> USAGE;
            case "error" -> ERROR;
            case "cancelled" -> CANCELLED;
            case "done" -> DONE;
            default -> UNKNOWN;
        };
    }
}
