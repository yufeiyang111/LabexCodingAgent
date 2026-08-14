package com.labex.labexagent.runtime;

public final class AgentToolCallIdPolicy {
    public static final int MAX_LENGTH = 256;

    private AgentToolCallIdPolicy() {
    }

    public static boolean isValid(String toolCallId) {
        return validationError(toolCallId).isEmpty();
    }

    public static String validationError(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return "toolCallId is required";
        }
        if (toolCallId.length() > MAX_LENGTH) {
            return "toolCallId is too long";
        }
        boolean hasUnsafeControl = toolCallId.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.LINE_SEPARATOR
                        || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
        return hasUnsafeControl ? "toolCallId contains control characters" : "";
    }
}
