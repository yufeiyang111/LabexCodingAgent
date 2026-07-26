package com.labex.labexagent.run;

import java.util.Locale;

public enum AgentRunState {
    QUEUED,
    PREPARING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER,
    WAITING_WORKSPACE,
    WAITING_ENVIRONMENT,
    RECOVERING,
    RETRYING,
    CANCELLING,
    CANCELLED,
    FAILED,
    COMPLETED;

    public String persistedStatus() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AgentRunState fromPersistedStatus(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("pending".equals(normalized)) {
            return QUEUED;
        }
        for (AgentRunState state : values()) {
            if (state.persistedStatus().equals(normalized)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown agent run state: " + value);
    }
}
