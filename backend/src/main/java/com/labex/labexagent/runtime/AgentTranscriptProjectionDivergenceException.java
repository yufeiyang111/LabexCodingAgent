package com.labex.labexagent.runtime;

/** 持久化运行时边界说明。 */
public final class AgentTranscriptProjectionDivergenceException extends RuntimeException {
    private final Long taskId;
    private final String detail;

    public AgentTranscriptProjectionDivergenceException(Long taskId, String detail) {
        super("Durable Provider transcript diverged: taskId=" + taskId + ", " + detail);
        this.taskId = taskId;
        this.detail = detail == null ? "" : detail;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getDetail() {
        return detail;
    }
}
