package com.labex.labexagent.context;

/** Provider 请求在发送前即可确定无法安全容纳时抛出的结构化异常。 */
public final class AgentContextOverflowException extends RuntimeException {
    private final String reasonCode;

    public AgentContextOverflowException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode == null || reasonCode.isBlank() ? "context_overflow" : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}